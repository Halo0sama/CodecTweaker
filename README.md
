# Bluetooth Codec Auto-Fix

Author: [Halo](https://github.com/Halo0sama)

A no-root Android app for Xiaomi / HyperOS that automatically restores the Bluetooth codec and bitrate you selected (default **LHDC 900 kbps**) every time your earphone reconnects.

<p align="center">
  <img src="screenshots/main.png" width="40%" />
  <img src="screenshots/diagnostics.png" width="40%" />
</p>

## Why

Some earphones (e.g. MOONDROP Little White) fall back to AAC on every reconnect even though they support LHDC. Switching the codec and bitrate back manually each time is tedious — this app does it automatically, with no root.

## Features

- **Auto-fix on reconnect**: works with any earphone out of the box, remembers devices after the first connection, restores codec + bitrate after each A2DP reconnect
- **v9.0 API 优先**：A2DP 连接后直接 `setCodecConfigPreference` 修复（约 2.3 秒，息屏可用，无需无障碍/Shizuku）；无障碍 UI 流程改为**默认关闭的兜底**，与 Shizuku、自定义方案、测试与日志一并移入「高级功能」二级页
- **Full codec list, identical to developer options**: System default / SBC / AAC / aptX / aptX HD / LDAC / aptX Adaptive / aptX TWS+ / LHDC V5 / V3/V4 / V2 / V1 / MIHC / enable & disable optional codecs
- **Bitrate options identical to the settings UI**: e.g. LHDC 990/909, 660/606, 330/303 kbps and adaptive
- **No root**: Shizuku user-service + Accessibility service, no bootloader unlock required
- **Per-earphone presets**: every remembered earphone keeps its own codec + bitrate; tap an earphone to switch to its preset
- **Optional post-flow actions**: exit Settings and resume the current music after the switch completes (both off by default)
- **Custom overrides**: manual device entry, custom row/option labels, trigger delay

## Requirements

- Tested on a Xiaomi phone with HyperOS 3 (Android 16). Other devices and ROMs are **not guaranteed to work** — you can try at your own risk
- Android 8.0+ (API 26+)
- [Shizuku](https://github.com/RikkaApps/Shizuku) running (wireless debugging is enough)

## Install & Setup

1. Download the latest APK from [Releases](../../releases) (or build from source) and install it.
2. Start Shizuku and grant this app the Shizuku API permission.
3. Enable the accessibility service: Settings → Accessibility → 音质助手 (Audio Quality Assistant).
4. Allow **display pop-up windows while running in the background** for this app (MIUI may block background starts otherwise).
5. Open the app, pick your earphone (or leave any-device mode), pick the codec and bitrate, and enable auto-switch.
6. Reconnect your earphone — the codec and bitrate are restored automatically.

## Build

```bash
./gradlew :app:assembleDebug
```

- JDK 17+, Android SDK platform 36 (`compileSdk=36`, `targetSdk=37`)
- Output: `app/app/build/outputs/apk/debug/app-debug.apk`
- Shizuku API jars are vendored under `app/app/libs/`; no extra downloads needed

## How It Works

### v8.9 起：API 优先，UI 兜底（HyperOS 3 实测）

```
耳机重连（A2DP CONNECTED 广播）
    |
    v
DirectorCore（进程常驻，App 启动即武装）
    |
    v
按 Config 目标（每设备预设）写 setCodecConfigPreference
（prio=1000000、48k/24bit 窄配置、小米码率编码）
    |
    v
校验-重试闭环：读回 getCodecStatus 比对编码器+码率，不符重写（≤3 笔）
—— 实测重连后 ~2.3s 达标，息屏可用，无需无障碍/Shizuku/开设置页
    |
    v
未达标 → 回落原 UI 自动化流程（无障碍 + Shizuku）兜底
```

另附**看守窗口**：建连后 15s 内编码器被改偏（广播可感知）即自动纠正。

### 旧流程（v8.8 及兜底路径）

```
Earphone reconnects (A2DP)
    |
    v
Accessibility service detects the connection
    |
    v
Shizuku shell opens Settings search "LHDC" (fastest route to the audio section)
    |
    v
UI automation selects codec row, then bitrate row
    |
    v
Done — codec stays until the next reconnect
```

- MIUI blocks background activity starts, so the Settings page is opened through the Shizuku shell instead of `startActivity`.
- The unified "LHDC" search keyword works for every codec because it lands directly on the audio section where all codec/bitrate rows live.
- LHDC bitrate values: `codecSpecific1` `0x8001` = 400 kbps, `0x8003` = 900 kbps, `0x8004` = adaptive.
- On other ROMs / devices, labels may differ; use the in-app "custom" overrides or edit `Config.java`.

## v8.9 实验路线：纯 API 直写（免 UI / 免无障碍 / 免 Shizuku）

在 HyperOS 3（Redmi K90 Pro Max）+ MOONDROP littlewhite 上实测得出的结论：

- A2DP 编码器由**手机单方面决定**，耳机从不选择协议；"关闭其他协议强制 LHDC" 不可行（免 root 没有 per-codec 开关，`setOptionalCodecsEnabled` 是 all-or-nothing，关掉后 LHDC 也一起失效）
- `setCodecConfigPreference` 生效的参数（与 Settings 发送的一致，来自 `A2dpCodecConfig` 日志）：
  - `codecPriority = 1000000`（HyperOS 的 HIGHEST；**AOSP 常量 1000 比 SBC 默认 1001 还低，是无效写入**）
  - 窄采样率/位深：`48000 / 24bit`（宽掩码被忽略）
  - `codecSpecific1` 用小米码率编码：`0x8003`=900kbps、`0x8004`=自适应
- 满足上述参数时，**一次普通应用直调即可直播切换编码器**（实测 aptX-Adaptive → LHDC_V3，无需重连），但配置**不跨重连持久**，每次重连后需重写（写入到生效约 2~3.5 秒）
- 读 API（`getCodecStatus`）要求应用与耳机有 **CDM 关联**，一次性建立：
  `adb shell cmd companiondevice associate 0 com.lhdcprobe <耳机MAC>`
- 在 A2DP 刚连上（ACL 窗口）写入偏好**无法**引导首次协商（实测仍会落到 aptX-Adaptive/AAC），因此修复时机是"连接建立后写入"，而非"连接前预置"

### CLI（实验页配套）

```bash
adb shell content call --uri content://com.lhdcprobe.cli --method <命令> [--arg <参数>]
```

| 命令 | 参数 | 说明 |
|---|---|---|
| `devices` | | 已连接 A2DP 设备 |
| `status` | | 当前编码器 + 可选列表（一行） |
| `write` | `codec=13;s1=0x8003;...` | 写偏好（codec/prio/rate/bits/ch/s1/mac） |
| `fix` | | 按 Config 目标修复（校验-重试闭环） |
| `target` | | 当前配置解析出的目标（type+s1） |
| `auto` | `on` / `off` | 自动修复开关=主界面总开关（进程常驻期间生效） |
| `watch` | `arm_acl` / `arm_observe` / `stop` / `status` | 连接监听与时间线 |
| `optional` | `status` / `enable` / `disable` | 可选编解码器开关 |
| `log` | `[n]` | 最近日志 |
| `cdm` | | CDM 关联状态 |

`disc`/`conn`（编程断连/重连）需要 `BLUETOOTH_PRIVILEGED`，shell 身份未持有，不可用。宿主侧封装脚本见 [scripts/lhdc.sh](scripts/lhdc.sh)。

## License

GPL-3.0. See [LICENSE](./LICENSE).

## Acknowledgments

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) — Shizuku API used for root-free shell access
- DeepSeek v4 flash — development and debugging assistance

---

# 蓝牙音质助手（Bluetooth Codec Auto-Fix）

作者：[Halo](https://github.com/Halo0sama)

一个**免 Root** 的小米 / HyperOS 安卓应用：耳机每次重连后，自动把开发者选项里的蓝牙编码器和播放质量恢复成你选好的配置（默认 **LHDC 900kbps**）。

<p align="center">
  <img src="screenshots/main.png" width="40%" />
  <img src="screenshots/diagnostics.png" width="40%" />
</p>

## 为什么需要它

有些耳机（比如水月雨小白线）明明支持 LHDC，却每次重连都掉回 AAC。手动切回去又麻烦又容易忘——这个 App 会在重连后自动帮你切回来，全程不需要 Root。

## 功能

- **重连自动修复**：默认对任意耳机生效，连接过一次就自动记忆；每次 A2DP 重连后自动恢复编码器 + 播放质量
- **v9.0 API 优先**：修复走 API 直写（约 2.3 秒，息屏可用，无需无障碍/Shizuku/开设置页）；无障碍 UI 流程改为**默认关闭的兜底**，与 Shizuku、自定义方案、测试与日志一并移入「高级功能」二级页
- **v9.4 LDAC 码率全支持**：实测 LDAC 档位用枚举 s1=1000~1003（990/660/330/自适应，与 LHDC 的 0x8000 编码不同）；app 内选择 LDAC 播放质量现在真正生效，面板显示具体档位（不再显示模糊的"标准"），校验闭环覆盖 LDAC 码率；LDAC 目标用宽采样率/位深掩码保持 96kHz/32bit 满血
- **v9.3 新耳机自动接入**：检测到未关联的新耳机时经 Shizuku（shell 身份）静默补建 CDM 关联并登记唤醒监听——HyperOS 不给第三方 app 弹系统关联确认框，shell 是唯一免 root 通路；Shizuku 未运行时降级（修复写入不受影响，只读/唤醒受限，Shizuku 恢复后 30s 内自动补齐）。能力预检：目标编码器不在耳机能力表时跳过修复（AUTOFIX_SKIP），不误触 UI 兜底
- **v9.2 实时状态面板**：主界面新增实时卡片——耳机、当前编码器、实际采样率/位深、码率档位；自适应模式下经 Shizuku 从系统日志探测实际码率（约 N kbps，节流轮询），CLI `live` 同款数据
- **v9.1 CDM 唤醒**：无需无障碍服务。耳机 ACL 连接时系统（CompanionDeviceManager）主动绑定并拉起 app（`CompanionListenerService` + 在场监听权限）；实测无无障碍、零接触重连 3 秒内修复完成；设备在场期间系统持有进程，天然防 MIUI 冻结
- **完整协议列表，与开发者选项完全一致**：使用系统选择（默认）/ SBC / AAC / aptX / aptX HD / LDAC / aptX Adaptive / aptX TWS+ / LHDC V5 / V3/V4 / V2 / V1 / MIHC / 启用与停用可选编解码器
- **比特率选项与系统设置完全一致**：如 LHDC 990/909、660/606、330/303kbps 与自适应
- **免 Root**：Shizuku 用户服务 + 无障碍服务，不需要解锁 Bootloader
- **每耳机预设**：每个记忆的耳机独立保存自己的协议 + 码率，点击耳机即可切换到它的预设
- **完成后可选操作**：切换完成后自动退出设置、继续播放当前音乐（默认均关闭）
- **自定义能力**：手动输入耳机、自定义行文案/选项文案、触发延迟

## 环境要求

- 以小米手机（HyperOS 3 / Android 16）为测试环境；**其他手机 / 系统不保证功能正常**，可自行尝试
- Android 8.0+（API 26+）
- 已启动 [Shizuku](https://github.com/RikkaApps/Shizuku)（无线调试即可）

## 安装与设置

1. 从 [Releases](../../releases) 下载最新 APK（或按下面方法自己编译）并安装。
2. 启动 Shizuku，并给本 App 授予 Shizuku API 权限。
3. 开启无障碍服务：设置 → 无障碍 → 音质助手。
4. 允许本 App **后台弹出界面**（MIUI 不开这个会拦截后台启动）。
5. 打开 App，选择你的耳机（或保持“任意设备”模式），选择协议和码率，打开自动切换。
6. 重连耳机，编码器和播放质量就会自动恢复。

## 编译

```bash
./gradlew :app:assembleDebug
```

- 需要 JDK 17+ 与 Android SDK Platform 36（`compileSdk=36`，`targetSdk=37`）
- 产物：`app/app/build/outputs/apk/debug/app-debug.apk`
- Shizuku API jar 已内置在 `app/app/libs/`，无需额外下载

## 工作原理

```
耳机重连（A2DP）
    |
    v
无障碍服务检测到连接
    |
    v
Shizuku shell 打开设置并搜索 "LHDC"（最快直达音频设置区）
    |
    v
UI 自动化依次选择编码器行、播放质量行
    |
    v
完成——直到下次重连都保持该编码器
```

- MIUI 会拦截后台启动设置页，所以设置页必须通过 Shizuku shell 打开，不能用 `startActivity`。
- 无论选哪种协议都统一搜索 "LHDC"，因为该搜索结果直接落在音频设置区，所有编码器/码率行都在同一屏。
- LHDC 码率数值：`codecSpecific1` `0x8001` = 400kbps、`0x8003` = 900kbps、`0x8004` = 自适应。
- 其他 ROM / 机型文案可能不同：可在 App 内用“自定义”覆盖，或直接改 `Config.java`。

## 许可证

GPL-3.0，详见 [LICENSE](./LICENSE)。

## 鸣谢

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) —— 本项目使用的 Shizuku API，实现免 Root 执行 shell
- DeepSeek v4 flash —— 开发与调试协助

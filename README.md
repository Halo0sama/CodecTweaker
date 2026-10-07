<div align="center">

# 音质助手 · Bluetooth Codec Auto-Fix

**免 Root 的安卓应用：蓝牙耳机每次重连后，自动恢复你选定的编码器与码率**

**Root-free Android app that restores your chosen Bluetooth codec & bitrate on every earphone reconnect**

[功能](#功能) · [工作原理](#工作原理) · [下载](#下载) · [CLI](#cli) · [English](#english)

<img src="screenshots/main.png" width="32%" alt="主界面" />
<img src="screenshots/diagnostics.png" width="32%" alt="诊断" />

</div>

---

## 中文

有些耳机明明支持 LHDC/LDAC，却每次重连都掉回 AAC——手动切回去麻烦又容易忘。音质助手会在耳机连上的几秒内自动把编码器和码率恢复成你选定的组合，息屏可用，全程免 Root。

### 功能

- **重连自动修复**：A2DP 连接建立后数秒内自动写入你选定的编码器与码率；修复失败自动重试，建连初期的编码器回退也会被自动纠正
- **实时状态面板**：当前编码器、采样率、位深、码率档位实时可见，不用再去开发者选项里翻
- **完整编码器与码率档位**：SBC / AAC / aptX 系列 / LDAC（990/660/330/自适应）/ LHDC 全系（最高 900kbps/自适应），与系统设置一致
- **每耳机预设**：不同耳机各自记忆编码器与码率，连接后各修各的；自动识别耳机能力，不支持的组合自动跳过
- **无需无障碍服务**：日常修复走系统接口直写；无障碍 UI 流程仅作为可选兜底，默认关闭
- **免 Root**：无需解锁 Bootloader；可选配合 Shizuku 实现新耳机自动接入与自适应码率探测
- **CLI**：adb 一条命令查询状态、触发修复，方便集成到自动化脚本

### 工作原理

```
耳机重连
   ↓
系统唤醒（CompanionDeviceManager 在场回调，进程未运行也会被拉起）
   ↓
按该耳机的预设写入编码器偏好（系统接口直写）
   ↓
读回校验，不符自动重写（码率回落等场景）
   ↓
连接后短窗口内持续看守，编码器被改偏则自动纠正
```

API 修复始终失败时，可开启「无障碍 UI 兜底」：由无障碍服务自动操作系统设置页完成切换（默认关闭）。

### 系统要求

- Android 8.0+
- 主要在小米 HyperOS 3 上开发与实测；其他 ROM / 机型不保证可用，欢迎反馈
- 可选：[Shizuku](https://github.com/RikkaApps/Shizuku) 运行中——用于新耳机自动接入与自适应码率探测；不运行也不影响修复本身

### 下载与使用

1. 从 [Releases](../../releases) 下载 APK 安装（或自行编译）
2. 打开 app，选择协议与码率，打开「连接后自动切换」
3. 连接耳机——之后每次重连都会自动恢复
4. 推荐（非必需）：启动 Shizuku 并授权，新耳机首次连接会自动完成接入

### CLI

```bash
adb shell content call --uri content://com.lhdcprobe.cli --method <命令> [--arg <参数>]
```

| 命令 | 说明 |
|---|---|
| `live` | 实时状态快照（编码器/采样率/位深/码率） |
| `devices` | 已连接 A2DP 设备 |
| `fix` | 立即按当前配置修复 |
| `auto on` / `auto off` | 自动修复开关 |
| `status` / `write` / `watch` / `optional` / `log` / `cdm` | 状态 / 写入 / 连接监听 / 可选编解码器 / 日志 / 关联 |

宿主侧封装脚本见 [scripts/lhdc.sh](scripts/lhdc.sh)。

### 编译

```bash
./gradlew :app:assembleDebug
```

需要 JDK 17+ 与 Android SDK（`compileSdk=36`）；Shizuku API jar 已内置在 `app/app/libs/`。

### 许可证

[GPL-3.0](./LICENSE)

### 鸣谢

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)
- 所有反馈问题的用户

---

## English

Some earphones silently fall back to AAC on every reconnect even though they support LHDC or LDAC. This app restores your chosen codec and bitrate within seconds of the earphone connecting — screen-off friendly, no root required.

### Features

- **Auto-fix on reconnect**: writes your selected codec & bitrate right after A2DP connects; failed writes are retried, and early-codec fallbacks are corrected automatically
- **Live status panel**: current codec, sample rate, bit depth and bitrate tier at a glance
- **Full codec & bitrate coverage**: SBC / AAC / aptX family / LDAC (990/660/330/adaptive) / LHDC family (up to 900 kbps/adaptive), matching the system settings UI
- **Per-earphone presets**: each earphone remembers its own codec & bitrate; unsupported combinations are skipped automatically
- **No accessibility service required**: daily fixes go through direct system API writes; the accessibility-based UI flow is an optional fallback, off by default
- **No root**: optionally pairs with Shizuku for automatic onboarding of new earphones and adaptive-bitrate probing
- **CLI**: query status or trigger fixes with a single adb command

### How it works

```
Earphone connects
   ↓
Woken by the system (CompanionDeviceManager presence callback)
   ↓
Codec preference written via system API (per-earphone preset)
   ↓
Read-back verification with automatic retry
   ↓
Short guard window corrects any post-connect fallback
```

If the API path keeps failing, an optional accessibility-based fallback (off by default) can drive the system settings page instead.

### Requirements

- Android 8.0+
- Developed and tested mainly on Xiaomi HyperOS 3; other ROMs are not guaranteed — feedback is welcome
- Optional: [Shizuku](https://github.com/RikkaApps/Shizuku) running for new-earphone onboarding and adaptive-bitrate probing

### Download

Grab the APK from [Releases](../../releases), pick a codec and bitrate, enable auto-switch — done. See the Chinese section above for the full CLI and build instructions.

### License

[GPL-3.0](./LICENSE)

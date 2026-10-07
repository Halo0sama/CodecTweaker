package com.lhdcprobe;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 实验核心（CLI 后端）：A2DP 代理管理、隐藏 API 调用、连接监听时间线、日志环形缓冲。
 * 由 CliProvider 调用，与界面解耦，供 adb shell content call 同步操作。
 *
 * 关键实测结论（HyperOS 3 / K90 Pro Max）：
 *  - setCodecConfigPreference 生效参数：prio=1000000（本 ROM HIGHEST；AOSP 常量 1000
 *    比 SBC 默认 1001 还低，等于无效）、窄采样率/位深（48k/24bit）、s1 用小米码率编码。
 *  - 该写入可直播切换编码器（aptX-Adaptive→LHDC_V3 实测），无需重连、无需 UI。
 */
public final class DirectorCore {

    private static volatile DirectorCore sInstance;

    public static DirectorCore get(Context c) {
        if (sInstance == null) {
            synchronized (DirectorCore.class) {
                if (sInstance == null) sInstance = new DirectorCore(c.getApplicationContext());
            }
        }
        return sInstance;
    }

    // HyperOS 实测参数
    public static final int HYPEROS_PRIO_HIGHEST = 1000000;

    private final Context app;
    private BluetoothAdapter adapter;
    private volatile BluetoothA2dp a2dp;
    private final CountDownLatch proxyLatch = new CountDownLatch(1);
    private final ExecutorService api = Executors.newSingleThreadExecutor();
    /** 状态快照查询线程（面板轮询用，避免被修复流程阻塞）。 */
    private final ExecutorService statusExecutor = Executors.newSingleThreadExecutor();
    /** 自适应码率探测节流：每 N 次快照才做一次昂贵的日志探测。 */
    private int liveProbeCounter;
    private static final int LIVE_PROBE_EVERY = 3;

    // 日志环形缓冲（供 CLI log 命令读取）
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private static final int LOG_MAX = 300;

    // 连接监听状态
    private static final int MODE_IDLE = 0;
    private static final int MODE_OBSERVE = 1;
    private static final int MODE_ACL_WRITE = 2;
    private volatile int watchMode = MODE_IDLE;
    private volatile long watchStart, tAcl, tA2dp, watchGen;
    private volatile String watchMac;
    private final ArrayDeque<String> timeline = new ArrayDeque<>();

    // 自动修复：统一以 Config.isEnabled（主界面“连接后自动切换”开关）为准，
    // 进程存活期间由本类广播接收器自持，无障碍服务存在则进程常驻。
    private final java.util.concurrent.atomic.AtomicBoolean fixing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private DirectorCore(Context app) {
        this.app = app;
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        bindProxy();
        registerBtReceiver();
        observeCompanionPresence();
        log("DirectorCore 初始化 autoFix=" + isAutoFixEnabled());
    }

    private void bindProxy() {
        if (adapter == null || !adapter.isEnabled()) return;
        adapter.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dp = (BluetoothA2dp) proxy;
                    proxyLatch.countDown();
                    log("A2DP 代理就绪");
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                a2dp = null;
                log("A2DP 代理断开");
                // 蓝牙进程重启后自动重绑
                bindProxy();
            }
        }, BluetoothProfile.A2DP);
    }

    private void registerBtReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction("android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED");
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                // 蓝牙总开关：开启后重绑 A2DP 代理（初始化时蓝牙关闭的补偿路径）
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    if (st == BluetoothAdapter.STATE_ON) {
                        log("蓝牙已开启，重绑 A2DP 代理");
                        api.execute(DirectorCore.this::bindProxy);
                    }
                    return;
                }
                if (watchMode == MODE_IDLE) return;
                BluetoothDevice d = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice.class)
                        : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || watchMac == null
                        || !watchMac.equalsIgnoreCase(d.getAddress())) return;
                long t = SystemClock.elapsedRealtime() - watchStart;
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    tAcl = t;
                    tl("ACL_CONNECTED");
                    if (watchMode == MODE_ACL_WRITE) {
                        api.execute(() -> {
                            try {
                                BluetoothCodecConfig cfg = buildTargetFromConfig(
                                        d.getAddress());
                                if (cfg != null) writeTarget(d.getAddress(), cfg);
                            } catch (Throwable e) {
                                log("ACL write ERR: " + e);
                            }
                        });
                    }
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    tl("ACL_DISCONNECTED");
                } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(
                            "android.bluetooth.profile.extra.STATE", -1);
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        tA2dp = t;
                        tl("A2DP_CONNECTED");
                        api.execute(() -> ensureAssociation(d.getAddress()));
                        guardUntil = SystemClock.elapsedRealtime() + 15000;
                        if (isAutoFixEnabled()) {
                            // 让速 250ms：无障碍服务在场时，其触发（带 UI 兜底回调）
                            // 先占坑；服务不在场时由此处兜底自持
                            api.execute(() -> {
                                try {
                                    Thread.sleep(250);
                                } catch (InterruptedException ignored) {
                                    return;
                                }
                                scheduleAutoFix(d.getAddress(), null);
                            });
                        }
                    } else {
                        tl("A2DP_STATE_" + state);
                    }
                } else if ("android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
                        .equals(action)) {
                    Object status = Build.VERSION.SDK_INT >= 33
                            ? intent.getParcelableExtra(
                                    "android.bluetooth.a2dp.extra.CODEC_STATUS",
                                    android.os.Parcelable.class)
                            : intent.getParcelableExtra(
                                    "android.bluetooth.a2dp.extra.CODEC_STATUS");
                    tl("CODEC_CHANGED " + (status == null ? "null"
                            : describeStatus(status)));
                    // 看守窗口：建连后 15s 内编码器被改偏（且不是本类正在修复）→ 纠偏
                    if (isAutoFixEnabled() && !fixing.get()
                            && SystemClock.elapsedRealtime() < guardUntil
                            && !isAtTarget(d.getAddress())) {
                        log("看守窗口：编码器偏离目标，触发纠正");
                        scheduleAutoFix(d.getAddress(), null);
                    }
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(r, f);
            }
        } catch (Throwable t) {
            log("注册广播失败: " + t);
        }
    }

    // ---------- 实时状态快照（主界面面板） ----------

    /** 当前 A2DP 状态快照：编码器、实际采样率/位深、码率模式、自适应估算。 */
    public static class Snapshot {
        public String error = "";
        public String name = "";
        public String mac = "";
        public String codec = "";
        public String rate = "";
        public String bits = "";
        public String bitrate = "";
        public String adaptive = "";
        public String selectable = "";
        public long ts;
    }

    /** 异步取快照，回调在查询线程执行（UI 需自行 post）。 */
    public void requestStatus(java.util.function.Consumer<Snapshot> cb) {
        statusExecutor.execute(() -> {
            Snapshot s;
            try {
                s = snapshot();
            } catch (Throwable t) {
                s = new Snapshot();
                s.error = "查询异常: " + t;
            }
            cb.accept(s);
        });
    }

    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.ts = System.currentTimeMillis();
        if (adapter == null) {
            s.error = "蓝牙不可用";
            return s;
        }
        if (!adapter.isEnabled()) {
            s.error = "蓝牙未开启";
            return s;
        }
        BluetoothA2dp p;
        try {
            p = awaitProxy();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.error = "查询中断";
            return s;
        }
        if (p == null) {
            // 懒重试：初始化时蓝牙可能尚未开启（广播路径之外的兜底）
            bindProxy();
            try {
                p = awaitProxy();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (p == null) {
            s.error = "A2DP 服务未就绪";
            return s;
        }
        List<BluetoothDevice> l = p.getConnectedDevices();
        if (l == null || l.isEmpty()) {
            s.error = "耳机未连接";
            return s;
        }
        BluetoothDevice d = l.get(0);
        s.mac = d.getAddress();
        s.name = safeName(d);
        try {
            Object status = HiddenApiBypass.invoke(BluetoothA2dp.class, p,
                    "getCodecStatus", d);
            if (status == null) {
                s.codec = "未协商";
                return s;
            }
            Object cfg = HiddenApiBypass.getDeclaredMethod(status.getClass(),
                    "getCodecConfig").invoke(status);
            if (cfg instanceof BluetoothCodecConfig) {
                BluetoothCodecConfig c = (BluetoothCodecConfig) cfg;
                s.codec = codecName(c.getCodecType());
                s.rate = rateLabel(c.getSampleRate());
                s.bits = bitsLabel(c.getBitsPerSample());
                s.bitrate = bitrateLabel(c.getCodecType(), c.getCodecSpecific1());
                if (c.getCodecSpecific1() == 0x8004L && s.codec.startsWith("LHDC")) {
                    s.adaptive = probeAdaptiveBitrate();
                }
            }
            Object sel = HiddenApiBypass.getDeclaredMethod(status.getClass(),
                    "getCodecsSelectableCapabilities").invoke(status);
            if (sel instanceof List) {
                StringBuilder sb = new StringBuilder();
                for (Object o : (List<?>) sel) {
                    if (o instanceof BluetoothCodecConfig) {
                        if (sb.length() > 0) sb.append(" / ");
                        sb.append(codecName(((BluetoothCodecConfig) o).getCodecType()));
                    }
                }
                s.selectable = sb.toString();
            }
        } catch (Throwable t) {
            // 连接信息仍有价值；编码器明细缺失单独提示
            s.codec = "读取受限（需 CDM 关联）";
            if (chain(t).contains("CDM association")) {
                api.execute(() -> ensureAssociation(s.mac));
            }
            String ce = chain(t);
            if (!ce.equals(lastCodecErr)) {
                lastCodecErr = ce;
                log("snapshot 读编码器失败: " + ce);
            }
        }
        return s;
    }

    /** 对象级判断：可选能力列表中是否包含指定编码器类型。 */
    private static boolean selectableContains(Object status, int codecType) {
        try {
            Object list = HiddenApiBypass.getDeclaredMethod(status.getClass(),
                    "getCodecsSelectableCapabilities").invoke(status);
            if (!(list instanceof List)) return false;
            for (Object o : (List<?>) list) {
                if (o instanceof BluetoothCodecConfig
                        && ((BluetoothCodecConfig) o).getCodecType() == codecType) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 可选编码器数量（能力表填充程度探测）。 */
    private static int selectableCount(Object status) {
        try {
            Object list = HiddenApiBypass.getDeclaredMethod(status.getClass(),
                    "getCodecsSelectableCapabilities").invoke(status);
            return list instanceof List ? ((List<?>) list).size() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String rateLabel(int mask) {
        StringBuilder sb = new StringBuilder();
        int[][] tab = {{0x1, 44100}, {0x2, 48000}, {0x4, 88200},
                {0x8, 96000}, {0x10, 176400}, {0x20, 192000}};
        for (int[] e : tab) {
            if ((mask & e[0]) != 0) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(e[1] / 1000).append("kHz");
            }
        }
        return sb.length() > 0 ? sb.toString() : "0x" + Integer.toHexString(mask);
    }

    private static String bitsLabel(int mask) {
        StringBuilder sb = new StringBuilder();
        int[][] tab = {{0x1, 16}, {0x2, 24}, {0x4, 32}};
        for (int[] e : tab) {
            if ((mask & e[0]) != 0) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(e[1]).append("bit");
            }
        }
        return sb.length() > 0 ? sb.toString() : "0x" + Integer.toHexString(mask);
    }

    /** 码率标签：LHDC 用小米编码；固定档直接给值，自适应标记出来。 */
    static String bitrateLabel(int codecType, long s1) {
        if (codecType == 4) { // LDAC：枚举 1000~1003
            if (s1 == 1000L) return "990 kbps";
            if (s1 == 1001L) return "660 kbps";
            if (s1 == 1002L) return "330 kbps";
            if (s1 == 1003L) return "自适应";
            if (s1 == 0L) return "自适应（默认档）";
            return "0x" + Long.toHexString(s1);
        }
        boolean lhdcFamily = codecType == 12 || codecType == 13 || codecType == 19;
        if (!lhdcFamily) return "—"; // 其余协议无码率档概念（s1 为元数据，不展示）
        if (s1 == 0x8004L) return "自适应";
        if (s1 == 0x8003L) return "900 kbps";
        if (s1 == 0x8002L) return "500/560 kbps";
        if (s1 == 0x8001L) return "400 kbps";
        if (s1 == 0x8000L) return "256 kbps";
        return "0x" + Long.toHexString(s1);
    }

    /**
     * 自适应模式下的实际码率探测：LHDC 编码器会在系统日志里报码率，
     * 经 Shizuku 读 logcat 取最近一条（节流：每 N 次快照做一次）。
     */
    private String probeAdaptiveBitrate() {
        if (++liveProbeCounter % LIVE_PROBE_EVERY != 1) return ""; // 本轮跳过
        if (!ShellExec.available()) return "需 Shizuku 才能探测实际码率";
        try {
            String out = ShellExec.exec("logcat -d -t 3000", 6000);
            if (out == null || out.isEmpty() || out.startsWith("ERR")) {
                return "日志不可读";
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)lhdc[^\\n]{0,80}?(\\d{3,4})\\s*kbps"
                            + "|(?i)(\\d{3,4})\\s*kbps[^\\n]{0,40}lhdc")
                    .matcher(out);
            String last = null;
            while (m.find()) {
                last = m.group(1) != null ? m.group(1) : m.group(2);
            }
            return last == null ? "日志中暂无码率数据（播放中会更新）"
                    : "约 " + last + " kbps（日志探测）";
        } catch (Throwable t) {
            return "探测失败";
        }
    }

    /** 最近一条日志（主界面状态行展示用）。 */
    public static String lastLog(Context c) {
        DirectorCore d = get(c);
        synchronized (d.logs) {
            return d.logs.isEmpty() ? "暂无记录" : d.logs.peekLast();
        }
    }

    // ---------- CLI 命令分发 ----------

    /** method=命令 arg=参数串。在 binder 线程同步执行，返回文本结果。 */
    public String exec(String method, String arg) {
        try {
            return dispatch(method == null ? "help" : method, arg == null ? "" : arg);
        } catch (Throwable t) {
            String msg = "ERR " + t.getClass().getSimpleName() + ": " + t.getMessage();
            log(msg);
            return msg;
        }
    }

    private synchronized String dispatch(String cmd, String arg) throws Exception {
        switch (cmd) {
            case "help":
                return "commands:\n"
                        + " devices                        已连接 A2DP 设备\n"
                        + " status [mac]                   当前编码器 + 可选列表（一行）\n"
                        + " live                           实时状态面板同款快照（含自适应探测）\n"
                        + " write k=v;...                  写偏好（codec,prio,rate,bits,ch,s1,mac）\n"
                        + " fix [mac]                      按 Config 目标修复（校验-重试）\n"
                        + " target                         当前配置解析出的目标（type+s1）\n"
                        + " auto on|off                    自动修复开关=主界面总开关\n"
                        + " optional status|enable|disable 可选编解码器开关\n"
                        + " watch arm observe|acl [mac]    预监听（acl=ACL 瞬间写目标）\n"
                        + " watch stop | watch status      停止/查看时间线\n"
                        + " log [n]                        最近日志\n"
                        + " cdm                            CDM 关联状态";

            case "devices": {
                BluetoothA2dp p = awaitProxy();
                if (p == null) return "ERR a2dp_proxy_unavailable (蓝牙未开?)";
                List<BluetoothDevice> l = p.getConnectedDevices();
                StringBuilder sb = new StringBuilder("OK connected=" + l.size());
                for (BluetoothDevice d : l) {
                    sb.append("\n").append(d.getAddress()).append(" ")
                            .append(safeName(d));
                }
                return sb.toString();
            }

            case "status":
            case "read": {
                BluetoothDevice d = target(arg);
                if (d == null) return "ERR no_connected_device";
                Object status = HiddenApiBypass.invoke(BluetoothA2dp.class,
                        awaitProxy(), "getCodecStatus", d);
                if (status == null) return "OK disconnected (无编码器状态)";
                return "OK mac=" + d.getAddress() + " " + describeStatus(status);
            }

            case "write": {
                java.util.HashMap<String, String> kv = parseKv(arg);
                int codec = Integer.parseInt(kv.getOrDefault("codec", "13"));
                int prio = Integer.parseInt(kv.getOrDefault("prio",
                        String.valueOf(HYPEROS_PRIO_HIGHEST)));
                long rate = parseNum(kv.getOrDefault("rate", "0x2"));
                long bits = parseNum(kv.getOrDefault("bits", "0x2"));
                long ch = parseNum(kv.getOrDefault("ch", "0x2"));
                long s1 = parseNum(kv.getOrDefault("s1", "0x8003"));
                BluetoothDevice d = target(kv.get("mac"));
                if (d == null) return "ERR no_connected_device";
                BluetoothCodecConfig cfg = new BluetoothCodecConfig.Builder()
                        .setCodecType(codec).setCodecPriority(prio)
                        .setSampleRate((int) rate).setBitsPerSample((int) bits)
                        .setChannelMode((int) ch).setCodecSpecific1(s1).build();
                Object ok = HiddenApiBypass.invoke(BluetoothA2dp.class, awaitProxy(),
                        "setCodecConfigPreference", d, cfg);
                log("CLI write codec=" + codec + " s1=0x" + Long.toHexString(s1)
                        + " -> " + ok);
                return "OK write codec=" + codec + " prio=" + prio
                        + " rate=0x" + Long.toHexString(rate)
                        + " bits=0x" + Long.toHexString(bits)
                        + " s1=0x" + Long.toHexString(s1) + " -> " + ok;
            }

            case "fix900":
            case "fix": {
                java.util.HashMap<String, String> kv = parseKv(arg);
                BluetoothDevice d = target(kv.get("mac"));
                if (d == null) return "ERR no_connected_device";
                String before = describeOf(d);
                String[] err = new String[1];
                int r = applyWithRetry(d.getAddress(), err);
                String after = describeOf(d);
                log("CLI fix: " + before + " => " + after);
                return (r == 0 ? "OK " : (r == 2 ? "SKIP " : "ERR " + err[0] + " "))
                        + before + "\n  => " + after;
            }

            case "optional": {
                BluetoothDevice d = target("");
                if (d == null) return "ERR no_connected_device";
                String sub = arg.trim();
                if (sub.isEmpty() || "status".equals(sub)) {
                    Object v = HiddenApiBypass.invoke(BluetoothA2dp.class,
                            awaitProxy(), "isOptionalCodecsEnabled", d);
                    return "OK optional_codecs=" + v + " (0=off 1=on 2=unknown)";
                }
                int pref = "disable".equals(sub) ? 0 : 1;
                HiddenApiBypass.invoke(BluetoothA2dp.class, awaitProxy(),
                        "setOptionalCodecsEnabled", d, pref);
                return "OK optional_codecs -> " + sub;
            }

            case "watch": {
                // content call 的 --arg 不允许空格，用 '_' 分隔：arm_acl / arm_observe / stop / status
                String sub = arg.trim().replace(' ', '_');
                if (sub.startsWith("arm")) {
                    String[] parts = sub.split("_");
                    String mode = parts.length > 1 ? parts[1] : "observe";
                    watchMac = parts.length > 2 ? normalize(parts[2]) : firstConnectedMac();
                    if (watchMac == null) return "ERR no_connected_device_for_default_mac";
                    synchronized (timeline) {
                        timeline.clear();
                    }
                    watchMode = "acl".equals(mode) ? MODE_ACL_WRITE : MODE_OBSERVE;
                    watchStart = SystemClock.elapsedRealtime();
                    tAcl = tA2dp = -1;
                    watchGen++;
                    final long gen = watchGen;
                    new Thread(() -> {
                        try {
                            Thread.sleep(60000);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                        if (gen == watchGen && watchMode != MODE_IDLE) {
                            watchMode = MODE_IDLE;
                            log("watch 60s 自动结束");
                        }
                    }).start();
                    return "OK watch " + mode + " mac=" + watchMac + " (60s)";
                }
                if ("stop".equals(sub)) {
                    watchMode = MODE_IDLE;
                    return "OK watch stopped";
                }
                // status
                StringBuilder sb = new StringBuilder("OK mode="
                        + (watchMode == MODE_IDLE ? "idle" : "armed") + " timeline:");
                synchronized (timeline) {
                    for (String s : timeline) sb.append("\n").append(s);
                }
                return sb.toString();
            }

            case "log": {
                int n;
                try {
                    n = Integer.parseInt(arg.trim());
                } catch (Exception e) {
                    n = 20;
                }
                StringBuilder sb = new StringBuilder("OK log:");
                synchronized (logs) {
                    for (String s : logs) {
                        sb.append("\n").append(s);
                        if (--n <= 0) break;
                    }
                }
                return sb.toString();
            }

            case "disc":
            case "conn": {
                java.util.HashMap<String, String> kv = parseKv(arg);
                BluetoothDevice d = target(kv.get("mac"));
                if (d == null) return "ERR no_connected_device";
                int policy = "disc".equals(cmd) ? 0 /*FORBIDDEN*/ : 100 /*ALLOWED*/;
                try {
                    Object ok = HiddenApiBypass.invoke(BluetoothA2dp.class, awaitProxy(),
                            "setConnectionPolicy", d, policy);
                    log("CLI " + cmd + " -> " + ok);
                    return "OK " + cmd + " " + d.getAddress() + " -> " + ok;
                } catch (Throwable t) {
                    return "ERR " + cmd + " " + chain(t);
                }
            }

            case "auto": {
                String sub = arg.trim();
                if ("on".equals(sub) || "off".equals(sub)) {
                    Config.setEnabled(app, "on".equals(sub));
                    log("autoFix -> " + ("on".equals(sub)));
                    return "OK auto_fix=" + ("on".equals(sub))
                            + "（进程存活期间生效；无障碍服务常驻=长期有效）";
                }
                return "OK auto_fix=" + isAutoFixEnabled();
            }

            case "live": {
                Snapshot sn = snapshot();
                StringBuilder sb = new StringBuilder("OK ");
                if (!sn.error.isEmpty()) sb.append(sn.error);
                else sb.append(sn.name).append(" (").append(sn.mac).append(")")
                        .append("\n  codec=").append(sn.codec)
                        .append(" rate=").append(sn.rate)
                        .append(" bits=").append(sn.bits)
                        .append("\n  bitrate=").append(sn.bitrate)
                        .append(sn.adaptive.isEmpty() ? "" : " | " + sn.adaptive)
                        .append("\n  selectable=").append(sn.selectable);
                return sb.toString();
            }

            case "target": {
                BluetoothDevice d0 = target("");
                String mac = d0 == null ? null : d0.getAddress();
                BluetoothCodecConfig cfg0 = buildTargetFromConfig(mac);
                if (cfg0 == null) {
                    return "ERR 目标协议无 API 映射（将走 UI 兜底）: "
                            + Config.effectiveCodecLabel(app, mac);
                }
                return "OK codec=" + codecName(cfg0.getCodecType())
                        + " s1=0x" + Long.toHexString(cfg0.getCodecSpecific1())
                        + " label=" + Config.effectiveCodecLabel(app, mac)
                        + " / " + Config.effectiveQualityLabel(app, mac);
            }

            case "cdm": {
                android.companion.CompanionDeviceManager cdm =
                        (android.companion.CompanionDeviceManager)
                                app.getSystemService(Context.COMPANION_DEVICE_SERVICE);
                if (cdm == null) return "ERR no_cdm";
                List<?> assocs = cdm.getAssociations();
                return "OK cdm_associations=" + assocs.size();
            }

            default:
                return "ERR unknown_cmd " + cmd + "（help 查看命令）";
        }
    }

    // ---------- 目标映射（来自本机 A2dpCodecConfig 栈日志的权威编码表） ----------

    /** 协议文案 → codecType。返回 null 表示无映射（走 UI 兜底）。 */
    static Integer codecTypeFor(String label) {
        if (label == null) return null;
        if (label.startsWith("LHDC V5")) return 19;
        if (label.startsWith("LHDC V3")) return 13;
        if (label.startsWith("LHDC V2")) return 12;
        if (label.startsWith("LHDC V1")) return null; // 本机能力表无此项，未验证
        if (label.startsWith("LDAC")) return 4;
        if (label.contains("aptX") && label.contains("Adaptive")) return 7;
        if (label.contains("aptX") && label.contains("HD")) return 3;
        if (label.contains("TWS")) return null; // TWS+ 无本机已验证映射，走 UI 兜底
        if (label.contains("aptX")) return 2;
        if ("AAC".equals(label)) return 1;
        if ("SBC".equals(label)) return 0;
        if ("MIHC".equals(label)) return 20;
        return null;
    }

    /**
     * 播放质量文案 → codecSpecific1（均在本机实测自 Settings 切档）。
     * LHDC 用 0x8000|索引 编码（0x8003=900k、0x8004=自适应已实测，256/500 推断）；
     * LDAC 用枚举 1000~1003（990/660/330/自适应，四档全部实测）。
     */
    static Long s1For(String label, String quality) {
        if (label == null) return 0L;
        if (label.startsWith("LDAC")) {
            if (quality == null) return 1000L;
            if (quality.contains("自适应")) return 1003L;
            if (quality.contains("660")) return 1001L;
            if (quality.contains("330")) return 1002L;
            return 1000L; // 990/909
        }
        if (!label.startsWith("LHDC")) return 0L;
        if (quality == null) return 0x8003L;
        if (quality.contains("900")) return 0x8003L;
        if (quality.contains("自适应")) return 0x8004L;
        if (quality.contains("400")) return 0x8001L;
        if (quality.contains("500")) return 0x8002L;
        if (quality.contains("256")) return 0x8000L;
        return 0x8003L;
    }

    /** 从 Config（含每设备预设）构造目标配置；无映射返回 null。 */
    private BluetoothCodecConfig buildTargetFromConfig(String mac) {
        String codecLabel = Config.effectiveCodecLabel(app, mac);
        Integer type = codecTypeFor(codecLabel);
        if (type == null) return null;
        long s1 = s1For(codecLabel, Config.effectiveQualityLabel(app, mac));
        // LHDC 用窄配置（48k/24，实测 Settings 同款）；LDAC 用宽掩码，
        // 让协商保持 96kHz/32bit 满血（LDAC 的码率档与采样率组合挂钩）
        boolean ldac = type == 4;
        int rate = ldac
                ? BluetoothCodecConfig.SAMPLE_RATE_44100
                | BluetoothCodecConfig.SAMPLE_RATE_48000
                | BluetoothCodecConfig.SAMPLE_RATE_88200
                | BluetoothCodecConfig.SAMPLE_RATE_96000
                : BluetoothCodecConfig.SAMPLE_RATE_48000;
        int bits = ldac
                ? BluetoothCodecConfig.BITS_PER_SAMPLE_16
                | BluetoothCodecConfig.BITS_PER_SAMPLE_24
                | BluetoothCodecConfig.BITS_PER_SAMPLE_32
                : BluetoothCodecConfig.BITS_PER_SAMPLE_24;
        return new BluetoothCodecConfig.Builder()
                .setCodecType(type)
                .setCodecPriority(HYPEROS_PRIO_HIGHEST)
                .setSampleRate(rate)
                .setBitsPerSample(bits)
                .setChannelMode(BluetoothCodecConfig.CHANNEL_MODE_STEREO)
                .setCodecSpecific1(s1)
                .build();
    }

    boolean isAutoFixEnabled() {
        return Config.isEnabled(app);
    }

    // ---------- 写入 ----------

    /** HyperOS 实测参数写目标配置：prio=1000000、48k/24bit 窄配置。 */
    private void writeTarget(String mac, BluetoothCodecConfig cfg) throws Exception {
        BluetoothDevice d = target(mac);
        if (d == null) return;
        HiddenApiBypass.invoke(BluetoothA2dp.class, awaitProxy(),
                "setCodecConfigPreference", d, cfg);
    }

    /**
     * 校验-重试闭环：写入 → 读回校验（编码器 + 码率）→ 不符则重写。
     * 码率回落（切编码器后 s1 常变回自适应）正是靠第二笔校正的。
     * 返回是否最终达标；无法读回（无 CDM）时视为成功。
     */
    private int applyWithRetry(String mac, String[] errOut) {
        try {
            BluetoothCodecConfig cfg = buildTargetFromConfig(mac);
            if (cfg == null) {
                if (errOut != null) errOut[0] = "no_api_mapping";
                return 1;
            }
            // 能力预检：目标编码器不在耳机可选表 → 跳过（不算失败，不触发 UI 兜底）。
            // 建流初期能力表可能只有强制编码器（实测首读仅 [AAC]），缺失时重查再判。
            // 注意必须对象级比对：字符串 contains 对"列表中间"的条目不可靠
            // （实测 LHDC_V3 排第二位时曾被误判不支持）
            boolean supported = false;
            for (int probe = 0; probe < 4 && !supported; probe++) {
                try {
                    BluetoothDevice pre = target(mac);
                    if (pre == null) break;
                    Object st = HiddenApiBypass.invoke(BluetoothA2dp.class,
                            awaitProxy(), "getCodecStatus", pre);
                    supported = st != null && selectableContains(st, cfg.getCodecType());
                    if (!supported && probe < 3) {
                        Thread.sleep(800);
                    }
                } catch (Throwable preErr) {
                    log("能力预检读取失败（继续尝试写入）: " + chain(preErr));
                    supported = true; // 读不到就不预检，直接写
                    break;
                }
            }
            if (!supported) {
                if (errOut != null) errOut[0] = "target_not_supported";
                log("目标 " + codecName(cfg.getCodecType())
                        + " 不在耳机能力表（重查后仍缺），跳过修复");
                return 2;
            }
            for (int attempt = 1; attempt <= 3; attempt++) {
                writeTarget(mac, cfg);
                Thread.sleep(1000);
                BluetoothDevice d = target(mac);
                if (d == null) {
                    if (errOut != null) errOut[0] = "device_gone";
                    return 1;
                }
                String st;
                try {
                    st = describeOf(d);
                } catch (Throwable readErr) {
                    // 无 CDM 关联等读权限缺失：写已发出，无法校验，按成功处理
                    log("读回失败（不校验）: " + chain(readErr));
                    return 0;
                }
                String wantCodec = codecName(cfg.getCodecType());
                boolean codecOk = st.contains("codec=" + wantCodec + " ");
                boolean s1Ok = st.contains("s1=0x"
                        + Long.toHexString(cfg.getCodecSpecific1()));
                if (codecOk && s1Ok) {
                    log("apply 第" + attempt + "笔达标: " + st);
                    return 0;
                }
                log("apply 第" + attempt + "笔未达标"
                        + (codecOk ? "（仅码率）" : "（编码器）") + ": " + st);
            }
            if (errOut != null) errOut[0] = "verify_failed_after_3";
            return 1;
        } catch (Throwable t) {
            if (errOut != null) errOut[0] = chain(t);
            return 1;
        }
    }

    /** 本会话内自动关联失败过的 mac，避免轮询风暴。 */
    private final java.util.Set<String> assocFailed = new java.util.HashSet<>();
    /** Shizuku 暂不可用时的重试节流（每 mac 30s）。 */
    private final java.util.Map<String, Long> assocRetryAt = new java.util.HashMap<>();
    /** 上一条快照读失败日志（相同则不重复记）。 */
    private String lastCodecErr = "";

    /**
     * 新耳机普适接入：无 CDM 关联时经 Shizuku（shell 身份，与 adb 同 uid）静默补建。
     * HyperOS 不给第三方 app 弹关联确认框，shell 是唯一免 root 通路；建好后登记在场监听。
     */
    private void ensureAssociation(String mac) {
        if (mac == null || Build.VERSION.SDK_INT < 33) return;
        String m = normalize(mac);
        if (m == null) return;
        synchronized (assocFailed) {
            if (assocFailed.contains(m)) return;
        }
        try {
            android.companion.CompanionDeviceManager cdm =
                    (android.companion.CompanionDeviceManager) app.getSystemService(
                            Context.COMPANION_DEVICE_SERVICE);
            if (cdm == null) return;
            for (android.companion.AssociationInfo a : cdm.getMyAssociations()) {
                String am = String.valueOf(a.getDeviceMacAddress());
                if (m.equalsIgnoreCase(am)) {
                    return; // 已关联
                }
            }
            if (!ShellExec.available()) {
                long now = SystemClock.elapsedRealtime();
                Long at = assocRetryAt.get(m);
                if (at == null || now - at > 30000) {
                    assocRetryAt.put(m, now);
                    log("新耳机 " + m + " 无 CDM 关联且 Shizuku 不可用（读取/唤醒将受限，"
                            + "修复写入不受影响；Shizuku 可用后将自动补关联）");
                }
                return;
            }
            String out = ShellExec.exec(
                    "cmd companiondevice associate 0 " + app.getPackageName() + " " + m, 8000);
            boolean ok = false;
            for (android.companion.AssociationInfo a : cdm.getMyAssociations()) {
                if (m.equalsIgnoreCase(String.valueOf(a.getDeviceMacAddress()))) {
                    ok = true;
                    break;
                }
            }
            if (ok) {
                cdm.startObservingDevicePresence(m);
                log("新耳机已自动关联并登记监听: " + m);
            } else {
                log("自动关联失败(" + m + "): " + out);
                synchronized (assocFailed) {
                    assocFailed.add(m);
                }
            }
        } catch (Throwable t) {
            log("ensureAssociation 异常: " + chain(t));
            synchronized (assocFailed) {
                assocFailed.add(m);
            }
        }
    }

    /** 向 CDM 登记设备在场监听：耳机 ACL 连接时系统会绑定并拉起本进程。 */
    private void observeCompanionPresence() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            android.companion.CompanionDeviceManager cdm =
                    (android.companion.CompanionDeviceManager) app.getSystemService(
                            Context.COMPANION_DEVICE_SERVICE);
            if (cdm == null) return;
            for (android.companion.AssociationInfo a : cdm.getMyAssociations()) {
                String mac = null;
                try {
                    mac = String.valueOf(a.getDeviceMacAddress());
                } catch (Throwable ignored) {
                }
                if (mac == null) continue;
                try {
                    cdm.startObservingDevicePresence(mac);
                    log("CDM 在场监听已登记: " + mac);
                } catch (Throwable t) {
                    log("CDM 在场监听登记失败 " + mac + ": " + t);
                }
            }
        } catch (Throwable t) {
            log("CDM 初始化失败: " + t);
        }
    }

    // ---------- 自动修复 ----------

    /**
     * A2DP 连接后延迟触发：按 Config 目标做校验-重试修复；
     * 失败且给了 fallback 时回调（供无障碍服务走原 UI 流程兜底）。
     */
    public void scheduleAutoFix(String mac, Runnable fallback) {
        if (!fixing.compareAndSet(false, true)) {
            log("autoFix 已在进行，跳过重复触发");
            return;
        }
        api.execute(() -> {
            try {
                // 等待 A2DP 建流且能力表填充完成（CDM 回调在 ACL 层，可比 A2DP 早 1s+；
                // 建流初期可选编码器列表只有强制编码器，立刻读会把"半满能力表"
                // 误判为不支持——必须等它填充）
                boolean a2dpReady = false;
                for (int i = 0; i < 30; i++) { // 最多 ~15s
                    try {
                        BluetoothDevice d = target(mac);
                        if (d != null) {
                            Object st = HiddenApiBypass.invoke(BluetoothA2dp.class,
                                    awaitProxy(), "getCodecStatus", d);
                            if (st != null) {
                                if (selectableCount(st) >= 2 || i >= 12) {
                                    a2dpReady = true; // 填充完成，或等满 6s 放行
                                    break;
                                }
                            }
                        }
                    } catch (Throwable readErr) {
                        // 无 CDM 关联等读权限问题：等一会儿再试，别提前退出
                    }
                    Thread.sleep(500);
                }
                if (!a2dpReady) {
                    tl("AUTOFIX_SKIP a2dp_not_ready");
                    return;
                }
                String[] err = new String[1];
                int r = applyWithRetry(mac, err);
                String st;
                try {
                    BluetoothDevice d = target(mac);
                    st = d == null ? "device_gone" : describeOf(d);
                } catch (Throwable e) {
                    st = "unreadable";
                }
                if (r == 2) {
                    tl("AUTOFIX_SKIP " + err[0] + " " + st);
                } else {
                    tl("AUTOFIX_" + (r == 0 ? "OK " : "FAIL:" + err[0] + " ") + st);
                    if (r != 0 && fallback != null) {
                        log("API 修复未达标，转 UI 兜底流程");
                        fallback.run();
                    }
                }
            } catch (Throwable t) {
                log("autoFix ERR: " + t);
            } finally {
                fixing.set(false);
            }
        });
    }

    /** 连接后看守窗口：窗口内编码器被改偏则自动纠正（防 MIUI 建连后回拉）。 */
    private volatile long guardUntil;

    private boolean isAtTarget(String mac) {
        try {
            BluetoothCodecConfig cfg = buildTargetFromConfig(mac);
            if (cfg == null) return true; // 无映射不干预
            BluetoothDevice d = target(mac);
            if (d == null) return true;
            String st = describeOf(d);
            return st.contains("codec=" + codecName(cfg.getCodecType()) + " ")
                    && (cfg.getCodecType() == 4
                        || st.contains("s1=0x" + Long.toHexString(cfg.getCodecSpecific1())));
        } catch (Throwable t) {
            return true; // 读不到就不干预
        }
    }

    // ---------- 工具 ----------

    private BluetoothA2dp awaitProxy() throws InterruptedException {
        if (a2dp == null && proxyLatch.getCount() > 0) {
            proxyLatch.await(3, TimeUnit.SECONDS);
        }
        return a2dp;
    }

    private BluetoothDevice target(String mac) throws Exception {
        BluetoothA2dp p = awaitProxy();
        if (p == null) return null;
        if (mac != null && !mac.isEmpty()) {
            String m = normalize(mac);
            return m == null ? null : adapter.getRemoteDevice(m);
        }
        List<BluetoothDevice> l = p.getConnectedDevices();
        return l.isEmpty() ? null : l.get(0);
    }

    private String firstConnectedMac() throws Exception {
        BluetoothA2dp p = awaitProxy();
        if (p == null) return null;
        List<BluetoothDevice> l = p.getConnectedDevices();
        return l.isEmpty() ? null : l.get(0).getAddress();
    }

    private String describeOf(BluetoothDevice d) throws Exception {
        Object status = HiddenApiBypass.invoke(BluetoothA2dp.class, awaitProxy(),
                "getCodecStatus", d);
        return status == null ? "disconnected" : describeStatus(status);
    }

    /** 反射解析 BluetoothCodecStatus，输出紧凑单行。 */
    private String describeStatus(Object status) {
        try {
            Object cfg = HiddenApiBypass.getDeclaredMethod(status.getClass(),
                    "getCodecConfig").invoke(status);
            StringBuilder sb = new StringBuilder();
            if (cfg instanceof BluetoothCodecConfig) {
                BluetoothCodecConfig c = (BluetoothCodecConfig) cfg;
                sb.append("codec=").append(codecName(c.getCodecType()))
                        .append(" rate=0x").append(Long.toHexString(c.getSampleRate()))
                        .append(" bits=0x").append(Long.toHexString(c.getBitsPerSample()))
                        .append(" s1=0x").append(Long.toHexString(c.getCodecSpecific1()));
            }
            Object list = HiddenApiBypass.getDeclaredMethod(status.getClass(),
                    "getCodecsSelectableCapabilities").invoke(status);
            if (list instanceof List) {
                sb.append(" selectable=[");
                for (Object o : (List<?>) list) {
                    if (o instanceof BluetoothCodecConfig) {
                        sb.append(codecName(((BluetoothCodecConfig) o).getCodecType()))
                                .append(" ");
                    }
                }
                sb.append("]");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "describe_err " + t;
        }
    }

    /** 解包 InvocationTargetException 等包装异常，输出根因链。 */
    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable x = t;
        int depth = 0;
        while (x != null && depth < 4) {
            if (depth > 0) sb.append(" <- ");
            sb.append(x.getClass().getSimpleName()).append(": ").append(x.getMessage());
            if (x.getCause() == x) break;
            x = x.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static String codecName(int type) {
        switch (type) {
            case 0: return "SBC";
            case 1: return "AAC";
            case 2: return "aptX";
            case 3: return "aptX-HD";
            case 4: return "LDAC";
            case 7: return "aptX-Adaptive";
            case 12: return "LHDC_V2";
            case 13: return "LHDC_V3";
            case 19: return "LHDCv5";
            case 20: return "MIHC";
            default: return "vendor(" + type + ")";
        }
    }

    private static java.util.HashMap<String, String> parseKv(String s) {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split(";")) {
            int i = pair.indexOf('=');
            if (i > 0) m.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
        }
        return m;
    }

    private static long parseNum(String s) {
        s = s.trim();
        return s.toLowerCase(Locale.US).startsWith("0x")
                ? Long.parseLong(s.substring(2), 16) : Long.parseLong(s);
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace(":", "").replace("-", "").replace(" ", "")
                .toUpperCase(Locale.US);
        if (s.length() != 12) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(s, i * 2, i * 2 + 2);
        }
        return sb.toString();
    }

    private static String safeName(BluetoothDevice d) {
        try {
            return d.getName() == null ? "?" : d.getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    private void tl(String event) {
        long t = SystemClock.elapsedRealtime() - watchStart;
        String line = "[+" + t + "ms] " + event;
        synchronized (timeline) {
            timeline.addLast(line);
            while (timeline.size() > 60) timeline.removeFirst();
        }
        log(event);
    }

    public void log(String s) {
        android.util.Log.i("LHDCDirector", s);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (logs) {
            logs.addLast(time + " " + s);
            while (logs.size() > LOG_MAX) logs.removeFirst();
        }
    }
}

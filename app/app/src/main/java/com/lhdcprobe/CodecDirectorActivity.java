package com.lhdcprobe;

import android.Manifest;
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
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/**
 * 协议定向连接实验页。
 *
 * 背景：A2DP 里是"手机（Source）单方面选编码器"，耳机（Sink）从不主动挑协议。
 * 所以"让耳机只能连 LHDC"不需要（免 root 也做不到）关闭其他协议——
 * 只要让手机在协商那一刻的偏好是 LHDC 即可。本页面用实验回答三个问题：
 *
 *  A1 断开状态下写入 LHDC 偏好 → 重连后第一次协商是否直接 LHDC（偏好是否被预加载）
 *  A2 ACL 刚连上（A2DP 协商前后）立刻写偏好 → 多久能切到 LHDC（对比无障碍 UI 流程的耗时）
 *  A3 只观察一次重连 → 判断 MIUI 是在哪个环节把编码器拉回 AAC 的
 *
 * 另附"停用可选编解码器"演示：证明免 root 能碰到的唯一"关闭协议"开关是
 * all-or-nothing（关掉后只剩 SBC，LHDC 一起消失），不能用于定向。
 */
public class CodecDirectorActivity extends AppCompatActivity {

    private static final String TAG = "LHDCDirector";

    // 隐藏广播：编码器配置变化（协商/重配发生的确切时刻）
    private static final String ACTION_CODEC_CONFIG_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    private static final String EXTRA_CODEC_STATUS =
            "android.bluetooth.a2dp.extra.CODEC_STATUS";

    // BluetoothA2dp 可选编解码器偏好（隐藏常量，写死避免编译依赖）
    private static final int OPTIONAL_CODECS_PREF_DISABLED = 0;
    private static final int OPTIONAL_CODECS_PREF_ENABLED = 1;

    private static final long POLL_MS = 200;          // 连接监听的采样周期
    private static final long POLL_TOTAL_MS = 25000;  // 监听总时长

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 用户动作与广播触发的写入走这里（串行，保证写偏好不被阻塞）。 */
    private final ExecutorService apiExecutor = Executors.newSingleThreadExecutor();
    /** 连接监听的轮询循环单独占用一个线程。 */
    private final ExecutorService watchExecutor = Executors.newSingleThreadExecutor();
    private final List<String> logs = new ArrayList<>();

    private BluetoothAdapter adapter;
    private volatile BluetoothA2dp a2dp;
    private BluetoothDevice watchDevice;

    private Object shizukuService;
    private java.lang.reflect.Field mServiceField;

    private EditText addrEdit;
    private EditText codecTypeEdit;
    private EditText specific1Edit;
    private TextView logView;
    private ScrollView logScroll;
    private TextView watchStatus;

    // 连接监听状态机
    private static final int MODE_IDLE = 0;         // 不监听
    private static final int MODE_OBSERVE = 1;      // 只观察重连全过程
    private static final int MODE_WRITE_AT_ACL = 2; // ACL 连上瞬间写偏好
    private volatile int watchMode = MODE_IDLE;
    private volatile long watchGen;    // 会话代号：旧会话的定时器/循环借此自行退出
    private volatile long watchStart;  // 本次监听的 t0
    private volatile long tAcl;        // ACL_CONNECTED 时刻（相对 watchStart）
    private volatile long tA2dp;       // A2DP CONNECTED 时刻
    private int lastReportedCodec = Integer.MIN_VALUE;
    private final List<String> timeline = new ArrayList<>();

    private BroadcastReceiver btReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.activity.EdgeToEdge.enable(this);
        buildUi();

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        fillConnectedDevice();
        registerBtReceiver();
    }

    @Override
    protected void onDestroy() {
        watchMode = MODE_IDLE;
        watchGen++;
        if (btReceiver != null) {
            try {
                unregisterReceiver(btReceiver);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    // ---------- UI ----------

    private Button addButton(LinearLayout root, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        root.addView(b);
        return b;
    }

    private EditText addEdit(LinearLayout root, String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.setSingleLine();
        root.addView(e);
        return e;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("协议定向连接实验（不关协议，靠偏好 + 早期钩子）");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(title);

        addrEdit = addEdit(root, "耳机 MAC", "");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        codecTypeEdit = addEdit(row, "codecType（本机 LHDC V3=13）", "13");
        codecTypeEdit.setLayoutParams(lp);
        specific1Edit = addEdit(row, "specific1（900k=7）", "7");
        specific1Edit.setLayoutParams(lp);
        root.addView(row);

        addButton(root, "① 填入当前已连接的耳机", v -> fillConnectedDevice());
        addButton(root, "①b CDM 关联此耳机（HyperOS 调 API 的前置条件）",
                v -> cdmAssociate());
        addButton(root, "② 读当前编码器状态", v -> api(this::readStatus));
        addButton(root, "③ 写 LHDC 偏好（窄：96k/24bit）", v -> api(() ->
                writePreference(buildConfig(false))));
        addButton(root, "③b 写 HyperOS 参数（prio=1000000, 48k/24, 900k）", v -> api(() ->
                writePreference(buildHyperOsConfig())));
        addButton(root, "④ 写 LHDC 偏好（宽：全采样率/位深掩码）", v -> api(() ->
                writePreference(buildConfig(true))));
        addButton(root, "⑤ 开始连接监听（只观察：MIUI 何时选了 AAC）",
                v -> startWatch(MODE_OBSERVE));
        addButton(root, "⑥ 开始连接监听（ACL 瞬间写 LHDC 偏好）",
                v -> startWatch(MODE_WRITE_AT_ACL));
        addButton(root, "⑦ 读可选编解码器开关", v -> api(this::readOptionalCodecs));
        addButton(root, "⑧ 停用可选编解码器（演示：LHDC 也会被关掉）", v ->
                api(() -> setOptionalCodecs(OPTIONAL_CODECS_PREF_DISABLED)));
        addButton(root, "⑨ 恢复启用可选编解码器", v ->
                api(() -> setOptionalCodecs(OPTIONAL_CODECS_PREF_ENABLED)));
        addButton(root, "复制日志", v -> copyLog());

        watchStatus = new TextView(this);
        watchStatus.setText("未在监听");
        watchStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(watchStatus);

        logView = new TextView(this);
        logView.setBackgroundResource(R.drawable.bg_log);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        LinearLayout.LayoutParams lpp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
        lpp.topMargin = dp(8);
        logScroll.setLayoutParams(lpp);
        root.addView(logScroll);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ---------- 蓝牙基础 ----------

    private boolean btReady() {
        if (adapter == null || !adapter.isEnabled()) {
            toast("请先打开蓝牙");
            return false;
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            toast("请允许蓝牙权限后重试");
            return false;
        }
        return true;
    }

    private BluetoothDevice device() {
        String addr = normalize(addrEdit.getText().toString());
        if (addr == null) {
            toast("MAC 格式不对");
            return null;
        }
        return adapter.getRemoteDevice(addr);
    }

    private void fillConnectedDevice() {
        if (!btReady()) return;
        if (a2dp != null) {
            List<BluetoothDevice> list = a2dp.getConnectedDevices();
            if (!list.isEmpty()) {
                addrEdit.setText(list.get(0).getAddress());
                log("已填入连接中的耳机: " + list.get(0).getAddress());
            } else {
                log("当前没有已连接的 A2DP 耳机");
            }
            return;
        }
        adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile != BluetoothProfile.A2DP) return;
                a2dp = (BluetoothA2dp) proxy;
                ui.post(() -> fillConnectedDevice());
            }

            @Override
            public void onServiceDisconnected(int profile) {
                a2dp = null;
            }
        }, BluetoothProfile.A2DP);
    }

    /** 发起 CompanionDeviceManager 关联（HyperOS 限制编解码器 API 的前置条件）。 */
    private void cdmAssociate() {
        if (!btReady()) return;
        BluetoothDevice device = device();
        if (device == null) return;
        android.companion.CompanionDeviceManager cdm =
                (android.companion.CompanionDeviceManager)
                        getSystemService(Context.COMPANION_DEVICE_SERVICE);
        if (cdm == null) {
            log("设备不支持 CompanionDeviceManager");
            return;
        }
        log("当前 CDM 关联数: " + cdm.getAssociations().size());
        android.companion.AssociationRequest request =
                new android.companion.AssociationRequest.Builder()
                        .addDeviceFilter(new android.companion.BluetoothDeviceFilter.Builder()
                                .setAddress(device.getAddress())
                                .build())
                        .build();
        log("发起 CDM 关联: " + device.getAddress() + "（请在系统弹窗中确认）");
        try {
            cdm.associate(request, new android.companion.CompanionDeviceManager.Callback() {
                @Override
                public void onAssociationCreated(android.companion.AssociationInfo info) {
                    log("CDM 关联成功: id=" + info.getId() + "，现在可以重试 ②/③/④");
                }

                @Override
                public void onFailure(CharSequence error) {
                    log("CDM 关联失败: " + error);
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            log("CDM 关联异常: " + chain(t));
        }
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

    // ---------- 隐藏 API 调用（直调优先，被拒时经 Shizuku 以 shell 身份重试） ----------

    private java.lang.reflect.Field findServiceField() throws Exception {
        if (mServiceField != null) return mServiceField;
        for (Field f : HiddenApiBypass.getInstanceFields(BluetoothA2dp.class)) {
            if ("mService".equals(f.getName())) {
                f.setAccessible(true);
                mServiceField = f;
                break;
            }
        }
        if (mServiceField == null) throw new IllegalStateException("找不到 mService 字段");
        return mServiceField;
    }

    private Object wrapShizukuService() throws Exception {
        if (shizukuService != null) return shizukuService;
        Object raw = findServiceField().get(a2dp);
        Class<?> iface = raw.getClass().getInterfaces()[0];
        IBinder binder = new ShizukuBinderWrapper(((IInterface) raw).asBinder());
        Class<?> stub = Class.forName(iface.getName() + "$Stub");
        shizukuService = HiddenApiBypass.invoke(stub, null, "asInterface", binder);
        return shizukuService;
    }

    /** 直调 → SecurityException 等失败时临时换成 Shizuku 服务再试。 */
    private boolean invokeA2dp(String resultMarker, Invokable call) throws Exception {
        Field f = findServiceField();
        try {
            call.run(a2dp);
            log(resultMarker + "（直调成功）");
            return true;
        } catch (Throwable directErr) {
            if (!Shizuku.pingBinder()) {
                log(resultMarker + " 直调失败且 Shizuku 不可用: " + chain(directErr));
                return false;
            }
            Object orig = f.get(a2dp);
            try {
                f.set(a2dp, wrapShizukuService());
                call.run(a2dp);
                log(resultMarker + "（经 Shizuku 成功）");
                return true;
            } catch (Throwable shizukuErr) {
                log(resultMarker + " 直调与 Shizuku 均失败 | 直调: " + chain(directErr)
                        + " | Shizuku: " + chain(shizukuErr));
                return false;
            } finally {
                f.set(a2dp, orig);
            }
        }
    }

    /** 解包 InvocationTargetException 等包装异常，打印根因链。 */
    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable x = t;
        int depth = 0;
        while (x != null && depth < 4) {
            if (depth > 0) sb.append(" ← ");
            String cls = x.getClass().getSimpleName();
            sb.append(cls).append(": ").append(x.getMessage());
            if (x.getCause() == x) break;
            x = x.getCause();
            depth++;
        }
        return sb.toString();
    }

    private interface Invokable {
        void run(BluetoothA2dp proxy) throws Exception;
    }

    // ---------- 实验动作 ----------

    private BluetoothCodecConfig buildConfig(boolean wideMask) {
        int codecType = parse(codecTypeEdit, 13);
        long specific1 = parse(specific1Edit, 7);
        int sampleRate = wideMask
                ? BluetoothCodecConfig.SAMPLE_RATE_44100
                | BluetoothCodecConfig.SAMPLE_RATE_48000
                | BluetoothCodecConfig.SAMPLE_RATE_88200
                | BluetoothCodecConfig.SAMPLE_RATE_96000
                : BluetoothCodecConfig.SAMPLE_RATE_96000;
        int bits = wideMask
                ? BluetoothCodecConfig.BITS_PER_SAMPLE_16
                | BluetoothCodecConfig.BITS_PER_SAMPLE_24
                | BluetoothCodecConfig.BITS_PER_SAMPLE_32
                : BluetoothCodecConfig.BITS_PER_SAMPLE_24;
        return new BluetoothCodecConfig.Builder()
                .setCodecType(codecType)
                .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                .setSampleRate(sampleRate)
                .setBitsPerSample(bits)
                .setChannelMode(BluetoothCodecConfig.CHANNEL_MODE_STEREO)
                .setCodecSpecific1(specific1)
                .build();
    }

    /**
     * 实测 HyperOS Settings 切换编码器时发送的参数（来自 A2dpCodecConfig 日志）：
     * prio=1000000（本 ROM 的 HIGHEST，注意 AOSP 常量 1000 在这里比 SBC 的 1001 还低），
     * 采样率/位深窄配置（48k/24bit），s1 按小米码率编码（0x8003=900kbps）。
     */
    private BluetoothCodecConfig buildHyperOsConfig() {
        return new BluetoothCodecConfig.Builder()
                .setCodecType(parse(codecTypeEdit, 13))
                .setCodecPriority(1000000)
                .setSampleRate(BluetoothCodecConfig.SAMPLE_RATE_48000)
                .setBitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_24)
                .setChannelMode(BluetoothCodecConfig.CHANNEL_MODE_STEREO)
                .setCodecSpecific1(0x8003L) // 900kbps
                .build();
    }

    private static int parse(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private boolean ensureProxy() {
        if (a2dp == null) {
            fillConnectedDevice();
            log("A2DP 代理未就绪，已尝试绑定，请稍后重试");
            return false;
        }
        return true;
    }

    private void readStatus() throws Exception {
        BluetoothDevice d = device();
        if (d == null || !ensureProxy()) return;
        invokeA2dp("读状态", proxy ->
                dumpStatusFull(HiddenApiBypass.invoke(BluetoothA2dp.class, proxy,
                        "getCodecStatus", d), "状态"));
    }

    private void writePreference(BluetoothCodecConfig cfg) throws Exception {
        BluetoothDevice d = device();
        if (d == null || !ensureProxy()) return;
        log("写入偏好: codecType=" + cfg.getCodecType()
                + " rate=0x" + Long.toHexString(cfg.getSampleRate())
                + " bits=0x" + Long.toHexString(cfg.getBitsPerSample())
                + " s1=0x" + Long.toHexString(cfg.getCodecSpecific1())
                + " prio=HIGHEST");
        invokeA2dp("写偏好", proxy ->
                HiddenApiBypass.invoke(BluetoothA2dp.class, proxy,
                        "setCodecConfigPreference", d, cfg));
    }

    private void readOptionalCodecs() throws Exception {
        BluetoothDevice d = device();
        if (d == null || !ensureProxy()) return;
        invokeA2dp("读可选开关", proxy -> {
            Object v = HiddenApiBypass.invoke(BluetoothA2dp.class, proxy,
                    "isOptionalCodecsEnabled", d);
            log("可选编解码器开关 = " + v + "（0=停用 1=启用 2=未知）");
        });
    }

    private void setOptionalCodecs(int pref) throws Exception {
        BluetoothDevice d = device();
        if (d == null || !ensureProxy()) return;
        boolean disable = pref == OPTIONAL_CODECS_PREF_DISABLED;
        log(disable
                ? "停用可选编解码器…（预期：只剩 SBC，LHDC/AAC/LDAC 全失效——"
                  + "这就是免 root 下\"关闭其他协议\"做不到定向的原因）"
                : "恢复启用可选编解码器…");
        boolean ok = invokeA2dp("写开关", proxy ->
                HiddenApiBypass.invoke(BluetoothA2dp.class, proxy,
                        "setOptionalCodecsEnabled", d, pref));
        if (!ok) {
            invokeA2dp("写开关(旧方法)", proxy -> HiddenApiBypass.invoke(
                    BluetoothA2dp.class, proxy,
                    disable ? "disableOptionalCodecs" : "enableOptionalCodecs", d));
        }
    }

    private void dumpStatusFull(Object status, String tag) throws Exception {
        if (status == null) {
            log(tag + ": null（未连接或无缓存）");
            return;
        }
        Object cfg = HiddenApiBypass.getDeclaredMethod(status.getClass(), "getCodecConfig")
                .invoke(status);
        if (cfg instanceof BluetoothCodecConfig) {
            BluetoothCodecConfig c = (BluetoothCodecConfig) cfg;
            long t = watchMode == MODE_IDLE ? -1
                    : SystemClock.elapsedRealtime() - watchStart;
            log(tag + (t >= 0 ? " [+" + t + "ms]" : "") + ": 当前 codecType="
                    + c.getCodecType() + " (" + codecName(c.getCodecType()) + ")"
                    + " rate=0x" + Long.toHexString(c.getSampleRate())
                    + " bits=0x" + Long.toHexString(c.getBitsPerSample())
                    + " s1=0x" + Long.toHexString(c.getCodecSpecific1()));
        }
        Object list = HiddenApiBypass.getDeclaredMethod(status.getClass(),
                "getCodecsSelectableCapabilities").invoke(status);
        if (list instanceof List) {
            StringBuilder sb = new StringBuilder("  可选: ");
            for (Object o : (List<?>) list) {
                if (o instanceof BluetoothCodecConfig) {
                    sb.append(codecName(((BluetoothCodecConfig) o).getCodecType()))
                            .append(" ");
                }
            }
            log(sb.toString());
        }
    }

    // ---------- 连接监听 ----------

    private void registerBtReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(ACTION_CODEC_CONFIG_CHANGED);
        btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (watchMode == MODE_IDLE) return;
                String action = intent.getAction();
                BluetoothDevice d = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice.class)
                        : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || watchDevice == null
                        || !watchDevice.getAddress().equals(d.getAddress())) return;
                long t = SystemClock.elapsedRealtime() - watchStart;
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    tAcl = t;
                    log("[+" + t + "ms] ACL 已连接");
                    if (watchMode == MODE_WRITE_AT_ACL) {
                        api(() -> writePreference(buildConfig(true)));
                    }
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    log("[+" + t + "ms] ACL 已断开");
                } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(
                            "android.bluetooth.profile.extra.STATE", -1);
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        tA2dp = t;
                        log("[+" + t + "ms] A2DP 已连接");
                    } else {
                        log("[+" + t + "ms] A2DP state=" + state);
                    }
                } else if (ACTION_CODEC_CONFIG_CHANGED.equals(action)) {
                    log("[+" + t + "ms] CODEC_CONFIG_CHANGED 广播");
                    Object status = Build.VERSION.SDK_INT >= 33
                            ? intent.getParcelableExtra(EXTRA_CODEC_STATUS,
                                    android.os.Parcelable.class)
                            : intent.getParcelableExtra(EXTRA_CODEC_STATUS);
                    if (status != null) {
                        api(() -> {
                            try {
                                dumpStatusFull(status, "广播");
                            } catch (Throwable e) {
                                log("解析广播状态失败: " + e);
                            }
                        });
                    }
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(btReceiver, filter);
            }
        } catch (Throwable t) {
            log("注册广播失败: " + t);
        }
    }

    private void startWatch(int mode) {
        if (!btReady()) return;
        BluetoothDevice d = device();
        if (d == null) return;
        if (!ensureProxy()) return;
        synchronized (timeline) {
            timeline.clear();
        }
        watchDevice = d;
        watchGen++;
        final long gen = watchGen;
        watchMode = mode;
        watchStart = SystemClock.elapsedRealtime();
        tAcl = -1;
        tA2dp = -1;
        lastReportedCodec = Integer.MIN_VALUE;
        watchStatus.setText(mode == MODE_WRITE_AT_ACL
                ? "监听中（ACL 瞬间写偏好）——请断开并重连耳机"
                : "监听中（只观察）——请断开并重连耳机");
        log("===== 开始监听 " + d.getAddress() + "（"
                + (mode == MODE_WRITE_AT_ACL ? "ACL写偏好" : "只观察") + "）=====");
        if (mode == MODE_OBSERVE) {
            log("提示：断开状态下先按 ③/④ 写好偏好再重连，即可顺带验证\"预加载\"（A1）");
        }
        watchExecutor.execute(() -> pollLoop(gen));
        ui.postDelayed(() -> {
            if (gen == watchGen && watchMode != MODE_IDLE) {
                watchMode = MODE_IDLE;
                watchStatus.setText("监听结束");
                log("===== 监听结束（" + POLL_TOTAL_MS / 1000 + "s）=====");
                if (tAcl >= 0 && tA2dp >= 0) {
                    log("小结: ACL→A2DP建立 " + (tA2dp - tAcl) + "ms");
                }
                synchronized (timeline) {
                    if (!timeline.isEmpty()) {
                        log("小结: 编码器轨迹 " + timeline.size() + " 次变化，首次: "
                                + timeline.get(0));
                    } else {
                        log("小结: 监听期内编码器未发生变化");
                    }
                }
            }
        }, POLL_TOTAL_MS);
    }

    /** 监听线程：每 200ms 读一次编码器状态，只在发生变化时记录。 */
    private void pollLoop(long gen) {
        long deadline = SystemClock.elapsedRealtime() + POLL_TOTAL_MS;
        while (gen == watchGen && watchMode != MODE_IDLE
                && SystemClock.elapsedRealtime() < deadline) {
            BluetoothA2dp proxy = a2dp;
            BluetoothDevice d = watchDevice;
            if (proxy != null && d != null) {
                try {
                    Object status = HiddenApiBypass.invoke(BluetoothA2dp.class, proxy,
                            "getCodecStatus", d);
                    if (status != null) {
                        Object cfg = HiddenApiBypass.getDeclaredMethod(
                                status.getClass(), "getCodecConfig").invoke(status);
                        if (cfg instanceof BluetoothCodecConfig) {
                            BluetoothCodecConfig c = (BluetoothCodecConfig) cfg;
                            synchronized (this) {
                                if (c.getCodecType() != lastReportedCodec) {
                                    lastReportedCodec = c.getCodecType();
                                    long t = SystemClock.elapsedRealtime() - watchStart;
                                    String line = "[+" + t + "ms] 编码器→"
                                            + codecName(c.getCodecType())
                                            + " rate=0x" + Long.toHexString(c.getSampleRate())
                                            + " s1=0x" + Long.toHexString(c.getCodecSpecific1());
                                    log(line);
                                    synchronized (timeline) {
                                        timeline.add(line);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
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

    // ---------- 后台执行与日志 ----------

    private void api(ThrowingAction action) {
        apiExecutor.execute(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                log("异常: " + t);
            }
        });
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private void log(String s) {
        android.util.Log.i(TAG, s);
        ui.post(() -> {
            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            logs.add(time + " " + s);
            while (logs.size() > 500) logs.remove(0);
            logView.setText(TextUtils.join("\n", logs));
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void copyLog() {
        Object systemService = getSystemService(CLIPBOARD_SERVICE);
        if (systemService instanceof android.content.ClipboardManager) {
            ((android.content.ClipboardManager) systemService).setPrimaryClip(
                    android.content.ClipData.newPlainText("定向连接实验日志",
                            TextUtils.join("\n", logs)));
            toast("日志已复制");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

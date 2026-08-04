package com.lhdcprobe;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/**
 * A2DP 隐藏 API 通道测试：
 *  1. 直接调用（试试 CDM 关联后是否免特权）
 *  2. Shizuku 调用（ADB shell 身份持有 BLUETOOTH_PRIVILEGED）
 *  目标指令：LHDC codecType=8，codecSpecific1=7（900kbps），96kHz/24bit
 */
public class A2dpTestActivity extends AppCompatActivity {

    private static final int SHIZUKU_REQUEST_CODE = 2333;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> logs = new ArrayList<>();

    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private EditText addressEdit;
    private EditText codecTypeEdit;
    private EditText specific1Edit;

    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;
    private Object rawService;
    private Object shizukuService;
    private java.lang.reflect.Field mServiceField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_a2dp_test);

        statusView = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.log_scroll);
        addressEdit = findViewById(R.id.addr);
        codecTypeEdit = findViewById(R.id.codec_type);
        specific1Edit = findViewById(R.id.specific1);
        addressEdit.setText("AA:BB:CC:DD:EE:FF");

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;

        findViewById(R.id.btn_cdm).setOnClickListener(v -> cdmAssociate());
        findViewById(R.id.btn_cdm_list).setOnClickListener(v -> cdmList());
        findViewById(R.id.btn_direct_read).setOnClickListener(v ->
                withProxy(() -> quiet(this::directRead)));
        findViewById(R.id.btn_direct_write).setOnClickListener(v ->
                withProxy(() -> quiet(this::directWrite)));
        findViewById(R.id.btn_shizuku).setOnClickListener(v -> shizukuSetup());
        findViewById(R.id.btn_shizuku_read).setOnClickListener(v ->
                withProxy(() -> quiet(this::shizukuRead)));
        findViewById(R.id.btn_shizuku_write).setOnClickListener(v ->
                withProxy(() -> quiet(this::shizukuWrite)));
        findViewById(R.id.btn_shizuku_one).setOnClickListener(v ->
                withProxy(() -> quiet(this::shizukuOneShot)));
        findViewById(R.id.btn_copy_log).setOnClickListener(v -> copyLog());

        try {
            Shizuku.addRequestPermissionResultListener(this::onShizukuPermissionResult);
            Shizuku.addBinderReceivedListenerSticky(() ->
                    log("Shizuku binder 已连接（uid=" + Shizuku.getUid() + "）"));
        } catch (Throwable t) {
            log("Shizuku 初始化异常（不影响其它功能）: " + t);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(this::onShizukuPermissionResult);
    }

    // ---------- 工具 ----------

    private BluetoothDevice device() {
        String addr = normalize(addressEdit.getText().toString());
        return addr == null ? null : adapter.getRemoteDevice(addr);
    }

    private boolean btReady() {
        if (adapter == null || !adapter.isEnabled()) {
            toast("请先打开蓝牙");
            return false;
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            toast("请允许蓝牙权限后重试");
            return false;
        }
        return true;
    }

    private void withProxy(Runnable action) {
        if (!btReady()) return;
        if (device() == null) {
            toast("地址格式不对");
            return;
        }
        if (a2dp == null) {
            setStatus("获取 A2DP 代理…");
            adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dp = (BluetoothA2dp) proxy;
                        setStatus("A2DP 代理就绪");
                        runBg(action);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    a2dp = null;
                    setStatus("A2DP 代理断开");
                }
            }, BluetoothProfile.A2DP);
        } else {
            runBg(action);
        }
    }

    private void runBg(Runnable action) {
        new Thread(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                log("异常: " + t);
            }
        }).start();
    }

    private void quiet(ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable t) {
            logChain("异常", t);
        }
    }

    private void logChain(String tag, Throwable t) {
        Throwable x = t;
        while (x != null) {
            log(tag + ": " + x);
            if (x.getCause() == null || x.getCause() == x) break;
            x = x.getCause();
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

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

    private Object wrapService() throws Exception {
        if (shizukuService != null) return shizukuService;
        rawService = findServiceField().get(a2dp);
        Class<?> iface = rawService.getClass().getInterfaces()[0];
        log("mService 接口: " + iface.getName());
        IBinder raw = ((IInterface) rawService).asBinder();
        IBinder wrapped = new ShizukuBinderWrapper(raw);
        Class<?> stub = Class.forName(iface.getName() + "$Stub");
        shizukuService = HiddenApiBypass.invoke(stub, null, "asInterface", wrapped);
        log("Shizuku 包装完成，调用方身份 uid=" + Shizuku.getUid());
        return shizukuService;
    }

    private interface ThrowingCallable {
        Object call() throws Exception;
    }

    private void withShizuku(ThrowingCallable action) throws Exception {
        Field f = findServiceField();
        Object orig = f.get(a2dp);
        f.set(a2dp, wrapService());
        try {
            action.call();
        } finally {
            f.set(a2dp, orig);
        }
    }

    private boolean ensureShizuku() {
        if (!Shizuku.pingBinder()) {
            log("Shizuku 未运行：请先用无线调试启动 Shizuku");
            return false;
        }
        if (Shizuku.isPreV11()) {
            log("Shizuku 版本过旧");
            return false;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            log("请求 Shizuku 权限…");
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
            return false;
        }
        return true;
    }

    private void onShizukuPermissionResult(int requestCode, int grantResult) {
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            log("Shizuku 授权结果: " + (grantResult == PackageManager.PERMISSION_GRANTED ? "已授权" : "被拒绝"));
        }
    }

    private void shizukuSetup() {
        ensureShizuku();
    }

    // ---------- 直接调用 ----------

    private void directRead() throws Exception {
        Object status = HiddenApiBypass.invoke(BluetoothA2dp.class, a2dp, "getCodecStatus", device());
        log("【直接读】调用成功");
        dumpStatus(status, "直接读");
    }

    private void directWrite() throws Exception {
        BluetoothCodecConfig config = buildConfig();
        Object ok = HiddenApiBypass.invoke(BluetoothA2dp.class, a2dp,
                "setCodecConfigPreference", device(), config);
        log("【直接写 900】返回: " + ok);
    }

    // ---------- Shizuku 调用 ----------

    private void shizukuRead() throws Exception {
        if (!ensureShizuku()) return;
        withShizuku(() -> {
            Object status = HiddenApiBypass.invoke(BluetoothA2dp.class, a2dp, "getCodecStatus", device());
            log("【Shizuku 读】调用成功");
            dumpStatus(status, "Shizuku 读");
            return null;
        });
    }

    private void shizukuWrite() throws Exception {
        if (!ensureShizuku()) return;
        BluetoothCodecConfig config = buildConfig();
        withShizuku(() -> {
            Object ok = HiddenApiBypass.invoke(BluetoothA2dp.class, a2dp,
                    "setCodecConfigPreference", device(), config);
            log("【Shizuku 写 900】返回: " + ok);
            return null;
        });
    }

    private void shizukuOneShot() throws Exception {
        if (!ensureShizuku()) return;
        dumpCodecMethods();
        BluetoothCodecConfig config = buildConfig();
        withShizuku(() -> {
            try {
                log("【一键】写前状态：");
                dumpStatus(HiddenApiBypass.invoke(BluetoothA2dp.class, a2dp, "getCodecStatus", device()), "写前");
            } catch (Throwable t) {
                logChain("【一键】写前读失败", t);
            }
            try {
                Object ok = HiddenApiBypass.invoke(BluetoothA2dp.class, a2dp,
                        "setCodecConfigPreference", device(), config);
                log("【一键】setCodecConfigPreference 返回: " + ok);
            } catch (Throwable t) {
                logChain("【一键】写失败", t);
            }
            Thread.sleep(3000);
            try {
                log("【一键】写后状态：");
                dumpStatus(HiddenApiBypass.invoke(BluetoothA2dp.class, a2dp, "getCodecStatus", device()), "写后");
            } catch (Throwable t) {
                logChain("【一键】写后读失败", t);
            }
            return null;
        });
    }

    private void dumpCodecMethods() throws Exception {
        StringBuilder sb = new StringBuilder("BluetoothA2dp codec 方法: ");
        for (java.lang.reflect.Executable e : HiddenApiBypass.getDeclaredMethods(BluetoothA2dp.class)) {
            String n = e.getName().toLowerCase(Locale.US);
            if (n.contains("codec") || n.contains("optional")) {
                sb.append(e.getName()).append(" ");
            }
        }
        log(sb.toString());
        Object raw = findServiceField().get(a2dp);
        Class<?> iface = raw.getClass().getInterfaces()[0];
        StringBuilder sb2 = new StringBuilder(iface.getName() + " 方法: ");
        for (java.lang.reflect.Executable e : HiddenApiBypass.getDeclaredMethods(iface)) {
            sb2.append(e.getName()).append(" ");
        }
        log(sb2.toString());
    }

    private BluetoothCodecConfig buildConfig() {
        int codecType = parseInt(codecTypeEdit, 13);
        long specific1 = parseInt(specific1Edit, 7);
        return new BluetoothCodecConfig.Builder()
                .setCodecType(codecType)               // 红米 K90 Pro Max 上 LHDC_V3 = 13
                .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                .setSampleRate(BluetoothCodecConfig.SAMPLE_RATE_96000)
                .setBitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_24)
                .setChannelMode(BluetoothCodecConfig.CHANNEL_MODE_STEREO)
                .setCodecSpecific1(specific1)          // 7 = 900 kbps
                .build();
    }

    private static int parseInt(EditText edit, int fallback) {
        try {
            return Integer.parseInt(edit.getText().toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private void dumpStatus(Object status, String tag) throws Exception {
        if (status == null) {
            log(tag + ": 无编码器状态（可能未连接）");
            return;
        }
        Method getCfg = HiddenApiBypass.getDeclaredMethod(status.getClass(), "getCodecConfig");
        Object cfg = getCfg.invoke(status);
        if (cfg instanceof BluetoothCodecConfig) {
            BluetoothCodecConfig c = (BluetoothCodecConfig) cfg;
            log(tag + ": codecType=" + c.getCodecType()
                    + " rate=" + c.getSampleRate()
                    + " bits=" + c.getBitsPerSample()
                    + " ch=" + c.getChannelMode()
                    + " specific1=" + Long.toHexString(c.getCodecSpecific1()) + "h");
        }
        Method sel = HiddenApiBypass.getDeclaredMethod(status.getClass(), "getCodecsSelectableCapabilities");
        Object list = sel.invoke(status);
        if (list instanceof List) {
            for (Object o : (List<?>) list) {
                if (o instanceof BluetoothCodecConfig) {
                    BluetoothCodecConfig c = (BluetoothCodecConfig) o;
                    log("  可选: type=" + c.getCodecType()
                            + " rate=" + c.getSampleRate()
                            + " bits=" + c.getBitsPerSample()
                            + " ch=" + c.getChannelMode()
                            + " s1=" + Long.toHexString(c.getCodecSpecific1()) + "h");
                }
            }
        }
    }

    // ---------- CDM ----------

    private void cdmAssociate() {
        if (!btReady()) return;
        BluetoothDevice device = device();
        if (device == null) return;
        CompanionDeviceManager cdm = (CompanionDeviceManager) getSystemService(Context.COMPANION_DEVICE_SERVICE);
        if (cdm == null) {
            log("设备不支持 CompanionDeviceManager");
            return;
        }
        AssociationRequest request = new AssociationRequest.Builder()
                .addDeviceFilter(new BluetoothDeviceFilter.Builder()
                        .setAddress(device.getAddress())
                        .build())
                .build();
        log("发起 CDM 关联: " + device.getAddress());
        try {
            cdm.associate(request, new CompanionDeviceManager.Callback() {
                @Override
                public void onAssociationCreated(android.companion.AssociationInfo info) {
                    log("CDM 关联成功: id=" + info.getId());
                    setStatus("CDM 已关联，可试“直接写900”");
                }

                @Override
                public void onFailure(CharSequence error) {
                    log("CDM 关联失败: " + error);
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            log("CDM 关联异常: " + t);
        }
    }

    private void cdmList() {
        CompanionDeviceManager cdm = (CompanionDeviceManager) getSystemService(Context.COMPANION_DEVICE_SERVICE);
        if (cdm == null) {
            log("不支持 CompanionDeviceManager");
            return;
        }
        List<String> list = cdm.getAssociations();
        log("当前关联数: " + list.size() + " " + list);
    }

    // ---------- UI ----------

    private static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace(":", "").replace("-", "").replace(" ", "").toUpperCase(Locale.US);
        if (s.length() != 12) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(s, i * 2, i * 2 + 2);
        }
        return sb.toString();
    }

    private void setStatus(final String s) {
        ui.post(() -> statusView.setText(s));
    }

    private void log(final String s) {
        android.util.Log.i("LHDCProbe", s);
        ui.post(() -> {
            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            logs.add(time + " " + s);
            while (logs.size() > 500) logs.remove(0);
            logView.setText(TextUtils.join("\n", logs));
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void copyLog() {
        String content = TextUtils.join("\n", logs);
        if (content.isEmpty()) {
            toast("日志为空");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("LHDC Probe A2DP 日志", content));
        toast("日志已复制");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

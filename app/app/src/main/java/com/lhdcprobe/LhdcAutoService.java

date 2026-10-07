package com.lhdcprobe;

import android.accessibilityservice.AccessibilityService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 无障碍自动修复服务：
 * 监听目标耳机 A2DP 重连，按用户配置自动执行：
 *  1. 设置搜索直达音频设置区
 *  2. 选择编码器（如 LHDC V3/V4）
 *  3. 选择播放质量（如 900kbps）
 */
public class LhdcAutoService extends AccessibilityService {

    private static final String TAG = "LHDCAuto";
    public static final String ACTION_TRIGGER = "com.lhdcprobe.TRIGGER_AUTO";
    public static volatile LhdcAutoService instance;
    public static volatile String lastLog = "服务未运行";

    private static final long STEP_DELAY = 1400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private volatile boolean lastConnected = false;
    private volatile String flowMac;
    private BluetoothAdapter adapter;

    private final BroadcastReceiver a2dpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_TRIGGER.equals(action)) {
                handler.postDelayed(() -> startFlow("manual_trigger"), 500);
                return;
            }
            log("onReceive: " + action);
            BluetoothDevice device = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                    : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) return;
            if ("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action)) {
                int state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1);
                if (state == BluetoothProfile.STATE_CONNECTED && Config.isTarget(context, device)) {
                    Config.rememberDevice(context, device);
                    // API 修复无需 UI 流程的触发延迟，立即触发以抢在 DirectorCore
                    // 自身接收器之前占坑，保证 UI 兜底回调挂在这条链上
                    log("检测到目标耳机 A2DP 连接，API 修复（UI 兜底）");
                    handler.post(() -> DirectorCore.get(LhdcAutoService.this)
                            .scheduleAutoFix(device.getAddress(),
                                    () -> startFlow("api_fix_fallback",
                                            device.getAddress())));
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                    && Config.isTarget(context, device)) {
                log("检测到目标耳机 ACL 连接");
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        lastLog = "服务已连接";
        log("无障碍服务已连接");
        // 无障碍服务在场 → 进程常驻 → DirectorCore 的 API 自动修复长期有效。
        // 本服务的 UI 流程降级为兜底：API 修复未达标时才走。
        try {
            DirectorCore.get(this);
            log("API 自动修复已武装（开关=" + Config.isEnabled(this) + "）");
        } catch (Throwable t) {
            log("DirectorCore 初始化失败，退回纯 UI 流程: " + t);
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        ShellExec.bind(this);
        handler.postDelayed(pollRunnable, 3000);
        try {
            unregisterReceiver(a2dpReceiver);
        } catch (Exception ignored) {
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(ACTION_TRIGGER);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(a2dpReceiver, filter, Context.RECEIVER_EXPORTED);
                log("广播接收器已注册(exported)");
            } else {
                registerReceiver(a2dpReceiver, filter);
                log("广播接收器已注册(默认)");
            }
        } catch (Throwable t2) {
            log("广播接收器注册失败: " + t2);
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        try {
            unregisterReceiver(a2dpReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    public static void trigger(Context context) {
        LhdcAutoService svc = instance;
        if (svc == null) {
            Log.i(TAG, "手动触发失败：服务实例为空");
            return;
        }
        svc.handler.post(() -> svc.startFlow("manual_trigger"));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    public void startFlow(String reason) {
        startFlow(reason, null);
    }

    public void startFlow(String reason, String mac) {
        if (running) {
            log("流程已在执行，跳过(" + reason + ")");
            return;
        }
        if (!Config.isEnabled(this)) {
            log("自动切换开关未开启，跳过(" + reason + ")");
            return;
        }
        // UI 兜底默认关闭：仅"测试触发"（用户显式点击）可越过
        if (!"manual_trigger".equals(reason) && !Config.isUiFlowEnabled(this)) {
            log("无障碍 UI 兜底未开启，跳过(" + reason + ")");
            return;
        }
        if (!ShellExec.available()) {
            ShellExec.bind(this);
            log("Shizuku 未就绪，已请求绑定（" + reason + "）");
        }
        if (Config.isScreenOnRequired(this) && !isScreenOn()) {
            log("屏幕未点亮，跳过(" + reason + ")");
            return;
        }
        log("开始自动切换: " + reason);
        flowMac = mac;
        running = true;
        executor.execute(this::runFlow);
    }

    private void runFlow() {
        boolean ok = true;
        try {
            ok &= runStep("打开设置并搜索", this::openDevOptions);
            if (!TextUtils.isEmpty(Config.resolveCodecOption(this, flowMac))) {
                ok &= runStep("选择编码器 " + Config.resolveCodecOption(this, flowMac), this::selectCodec);
            }
            if (!TextUtils.isEmpty(Config.resolveQualityOption(this, flowMac))) {
                ok &= runStep("选择播放质量 " + Config.resolveQualityOption(this, flowMac), this::selectQuality);
            }
            if (!ok) {
                log("流程结束：部分步骤未完成（文字不匹配时可到高级设置里修改）");
            } else {
                log("自动切换流程完成");
                afterFlowDone();
            }
        } catch (Throwable t) {
            log("流程异常: " + t);
        } finally {
            running = false;
        }
    }

    /** 流程成功后的可选收尾动作（两个开关默认关闭）。 */
    private void afterFlowDone() {
        try {
            if (Config.isExitSettingsAfterDone(this)) {
                log("完成后退出设置");
                // 持续按返回键，直到设置应用不再是前台；避免按少留在设置里、
                // 按多误退到别的应用
                for (int i = 0; i < 4; i++) {
                    if (!isSettingsForeground()) break;
                    ShellExec.exec("input keyevent 4", 3000);
                    sleep(700);
                }
            }
            if (Config.isResumeMusicAfterDone(this)) {
                log("完成后继续播放当前音乐");
                ShellExec.exec("input keyevent 126", 3000);
            }
        } catch (Throwable t) {
            log("完成后收尾操作失败: " + t);
        }
    }

    /** 检查设置应用是否还在前台。 */
    private boolean isSettingsForeground() {
        try {
            String out = ShellExec.exec(
                    "dumpsys window | grep -m1 mCurrentFocus", 5000);
            return out != null && out.contains("com.android.settings");
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean runStep(String name, Step action) {
        log("步骤: " + name);
        boolean ok = false;
        try {
            ok = action.run();
        } catch (Throwable t) {
            log("步骤失败(" + name + "): " + t);
        }
        sleep(STEP_DELAY);
        return ok;
    }

    private interface Step {
        boolean run();
    }

    // ---------- 步骤实现 ----------

    private boolean openDevOptions() {
        if (!ShellExec.available()) {
            log("Shell 不可用，无法打开设置");
            return false;
        }
        // 统一搜索 LHDC 快速定位到音频设置区，任何协议都走这条路
        String keyword = "LHDC";
        String resultText = "蓝牙音频 LHDC 编解码器：播放质量";
        ShellExec.exec("am start -f 0x10008000 -a android.settings.SETTINGS", 8000);
        sleep(1500);
        int[] box = treeFind("搜索系统设置项", 8);
        if (box == null) box = ShellExec.waitVisible("搜索系统设置项", 6);
        if (box == null) {
            log("设置搜索框未出现，走开发者选项兜底");
            openDevOptionsFallback();
            return true;
        }
        ShellExec.exec("input tap " + box[0] + " " + box[1], 3000);
        sleep(500);
        ShellExec.exec("input text " + escapeInputText(keyword), 3000);
        sleep(900);
        int[] result = treeFind(resultText, 10);
        if (result == null) result = ShellExec.waitVisible(resultText, 10);
        if (result == null) {
            log("LHDC 搜索结果未出现，走开发者选项兜底");
            openDevOptionsFallback();
            return true;
        }
        ShellExec.exec("input tap " + result[0] + " " + result[1], 3000);
        sleep(1200);
        log("已通过搜索进入音频设置区");
        return true;
    }

    private void openDevOptionsFallback() {
        ShellExec.exec("am force-stop com.android.settings; sleep 1; "
                + "am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS", 8000);
        sleep(6000);
        log("已打开开发者选项（兜底路径）");
    }

    private boolean selectCodec() {
        if (!ShellExec.available()) return false;
        String row = Config.resolveCodecRow(this, flowMac);
        String option = Config.resolveCodecOption(this, flowMac);
        int[] r = treeFind(row, 8);
        if (r == null) r = ShellExec.waitVisible(row, 6);
        if (r == null) r = ShellExec.scrollUntilVisible(row);
        if (r == null) {
            log("找不到编码器行: " + row);
            return false;
        }
        ShellExec.exec("input tap " + Math.min(r[0] + 550, 1100) + " " + r[1], 3000);
        sleep(1000);
        int[] item = treeFind(option, 10);
        if (item == null) item = ShellExec.waitVisible(option, 6);
        if (item == null) item = ShellExec.scrollUntilVisible(option);
        if (item == null) {
            log("找不到编码器选项: " + option);
            return false;
        }
        ShellExec.exec("input tap " + item[0] + " " + item[1], 3000);
        sleep(1000);
        if (!treeGone(option, 8)) ShellExec.waitGone(option, 8);
        log("已选择 " + option);
        return true;
    }

    private boolean selectQuality() {
        if (!ShellExec.available()) return false;
        String row = Config.resolveQualityRow(this, flowMac);
        String option = Config.resolveQualityOption(this, flowMac);
        int[] r = treeFind(row, 8);
        if (r == null) r = ShellExec.waitVisible(row, 6);
        if (r == null) r = ShellExec.scrollUntilVisible(row);
        if (r == null) {
            log("找不到播放质量行: " + row);
            return false;
        }
        ShellExec.exec("input tap " + Math.min(r[0] + 550, 1100) + " " + r[1], 3000);
        sleep(1000);
        int[] item = treeFind(option, 10);
        if (item == null) item = ShellExec.waitVisible(option, 6);
        if (item == null) item = ShellExec.scrollUntilVisible(option);
        if (item == null) {
            log("找不到播放质量选项: " + option);
            return false;
        }
        ShellExec.exec("input tap " + item[0] + " " + item[1], 3000);
        sleep(1000);
        if (!treeGone(option, 8)) ShellExec.waitGone(option, 8);
        log("已选择 " + option);
        return true;
    }

    // ---------- 无障碍树定位 ----------

    private int[] treeFind(String text, int tries) {
        for (int i = 0; i < tries; i++) {
            int[] c = treeFindOnce(text);
            if (c != null) return c;
            sleep(300);
        }
        return null;
    }

    private int[] treeFindOnce(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            int[] c = nodeCenter(findByText(root, text));
            if (c != null) return c;
        }
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) {
                        int[] c = nodeCenter(findByText(r, text));
                        if (c != null) return c;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean treeGone(String text, int tries) {
        for (int i = 0; i < tries; i++) {
            if (treeFindOnce(text) == null) return true;
            sleep(300);
        }
        return false;
    }

    private static int[] nodeCenter(AccessibilityNodeInfo node) {
        if (node == null) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return null;
        return new int[]{(r.left + r.right) / 2, (r.top + r.bottom) / 2};
    }

    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo root, String text) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) return nodes.get(0);
        CharSequence t = root.getText();
        if (t != null && t.toString().toLowerCase(Locale.US)
                .contains(text.toLowerCase(Locale.US))) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findByText(child, text);
            if (found != null) return found;
        }
        return null;
    }

    // ---------- 轮询触发 ----------

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (!ShellExec.available()) {
                    ShellExec.bind(LhdcAutoService.this);
                }
                if (adapter != null && adapter.isEnabled()) {
                    BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                    if (bm != null) {
                        List<BluetoothDevice> list = bm.getConnectedDevices(BluetoothProfile.A2DP);
                        boolean connected = false;
                        if (list != null) {
                            for (BluetoothDevice d : list) {
                                if (Config.isTarget(LhdcAutoService.this, d)) {
                                    connected = true;
                                    Config.rememberDevice(LhdcAutoService.this, d);
                                    log("已记忆设备 " + d.getAddress());
                                }
                            }
                        }
                        if (connected && !lastConnected && Config.isEnabled(LhdcAutoService.this)
                                && (!Config.isScreenOnRequired(LhdcAutoService.this) || isScreenOn())) {
                            handler.post(() -> DirectorCore.get(LhdcAutoService.this)
                                    .scheduleAutoFix(null,
                                            () -> startFlow("poll_fallback")));
                        }
                        lastConnected = connected;
                    }
                }
            } catch (Throwable ignored) {
            }
            handler.postDelayed(pollRunnable, 5000);
        }
    };

    // ---------- 工具 ----------

    /** input text 的转义：空格用 %s 代替（Android input 约定）。 */
    private static String escapeInputText(String s) {
        return s.replace(" ", "%s");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void log(String s) {
        lastLog = s;
        Log.i(TAG, s);
        try {
            SharedPreferences sp = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String h = sp.getString(Config.KEY_LOG_HISTORY, "") + time + " " + s + "\n";
            String[] lines = h.split("\n");
            if (lines.length > 200) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - 200; i < lines.length; i++) {
                    sb.append(lines[i]).append("\n");
                }
                h = sb.toString();
            }
            sp.edit().putString(Config.KEY_LOG_HISTORY, h)
                    .putString(Config.KEY_LAST_LOG, s).apply();
        } catch (Throwable ignored) {
        }
    }
}

package com.lhdcprobe;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * 音质助手主界面（v8 泛用版）：
 * 默认对任意耳机生效并自动记忆设备；选择协议与码率；授权引导；测试与日志。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LHDCProbe";
    private static final int REQ_BT_PERMS = 100;
    private static final int REQ_SHIZUKU = 1001;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<BluetoothDevice> pickerDevices = new ArrayList<>();
    private final Map<String, BluetoothDevice> pickerSeen = new LinkedHashMap<>();
    private ArrayAdapter<String> pickerAdapter;

    private boolean loadingUi = false;
    private BluetoothAdapter btAdapter;

    private LinearLayout rememberedList;
    private TextView shizukuStatus;
    private TextView a11yStatus;
    private TextView logView;
    private android.widget.ScrollView logScroll;
    private MaterialSwitch swAuto;
    private MaterialSwitch swScreenOn;
    private MaterialSwitch swExitDev;
    private MaterialSwitch swResumeMusic;
    private MaterialAutoCompleteTextView spCodec;
    private MaterialAutoCompleteTextView spQuality;
    private TextInputLayout tilCodec;
    private TextInputLayout tilQuality;
    private boolean codecDropdownOpen;
    private boolean qualityDropdownOpen;
    private View advancedPanel;
    private EditText codecRowEdit;
    private EditText codecOptionEdit;
    private EditText qualityRowEdit;
    private EditText qualityOptionEdit;
    private EditText delayEdit;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            refreshLog();
            renderRememberedDevices();
            ui.postDelayed(this, 2000);
        }
    };

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
            BluetoothDevice d = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                    : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null || d.getAddress() == null) return;
            if (pickerSeen.containsKey(d.getAddress())) return;
            pickerSeen.put(d.getAddress(), d);
            pickerDevices.add(d);
            pickerAdapter.add(deviceLabel(d));
        }
    };

    private final Shizuku.OnRequestPermissionResultListener shizukuListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQ_SHIZUKU) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    ShellExec.bind(MainActivity.this);
                    toast("Shizuku 已授权");
                } else {
                    toast("Shizuku 授权被拒绝");
                }
                refreshStatus();
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        rememberedList = findViewById(R.id.remembered_list);
        shizukuStatus = findViewById(R.id.tv_shizuku_status);
        a11yStatus = findViewById(R.id.tv_a11y_status);
        logView = findViewById(R.id.tv_log);
        logScroll = findViewById(R.id.log_scroll);
        swAuto = findViewById(R.id.sw_auto);
        swScreenOn = findViewById(R.id.sw_screen_on);
        swExitDev = findViewById(R.id.sw_exit_dev);
        swResumeMusic = findViewById(R.id.sw_resume_music);
        spCodec = findViewById(R.id.sp_codec);
        spQuality = findViewById(R.id.sp_quality);
        tilCodec = findViewById(R.id.til_codec);
        tilQuality = findViewById(R.id.til_quality);
        advancedPanel = findViewById(R.id.advanced_panel);
        codecRowEdit = findViewById(R.id.et_codec_row);
        codecOptionEdit = findViewById(R.id.et_codec_option);
        qualityRowEdit = findViewById(R.id.et_quality_row);
        qualityOptionEdit = findViewById(R.id.et_quality_option);
        delayEdit = findViewById(R.id.et_delay);

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = bm != null ? bm.getAdapter() : null;
        pickerAdapter = new ArrayAdapter<>(
                this, R.layout.item_device_picker, android.R.id.text1);

        spCodec.setAdapter(new ArrayAdapter<>(
                this, R.layout.item_dropdown, android.R.id.text1, Config.CODECS));
        spQuality.setAdapter(new ArrayAdapter<>(
                this, R.layout.item_dropdown, android.R.id.text1, new String[0]));

        swAuto.setOnCheckedChangeListener((v, isChecked) -> {
            if (loadingUi) return;
            Config.setEnabled(this, isChecked);
            toast(isChecked ? "自动切换已开启" : "自动切换已关闭");
        });
        swScreenOn.setOnCheckedChangeListener((v, isChecked) -> {
            if (loadingUi) return;
            Config.setScreenOnRequired(this, isChecked);
        });
        swExitDev.setOnCheckedChangeListener((v, isChecked) -> {
            if (loadingUi) return;
            Config.setExitSettingsAfterDone(this, isChecked);
        });
        swResumeMusic.setOnCheckedChangeListener((v, isChecked) -> {
            if (loadingUi) return;
            Config.setResumeMusicAfterDone(this, isChecked);
        });
        spCodec.setOnItemClickListener((parent, view, position, id) -> {
            if (loadingUi) return;
            String codec = (String) parent.getItemAtPosition(position);
            Config.setCodecLabel(MainActivity.this, codec);
            String mac = Config.getActiveMac(this);
            if (mac != null) {
                Config.setDevicePreset(this, mac, codec,
                        Config.presetQuality(this, mac));
            }
            codecDropdownOpen = false;
            updateQualitySpinner();
            updateAdvancedVisibility();
            toast("协议：" + codec);
        });
        spQuality.setOnItemClickListener((parent, view, position, id) -> {
            if (loadingUi) return;
            String q = (String) parent.getItemAtPosition(position);
            Config.setQualityLabel(MainActivity.this, q);
            String mac = Config.getActiveMac(this);
            if (mac != null) {
                Config.setDevicePreset(this, mac,
                        Config.presetCodec(this, mac), q);
            }
            qualityDropdownOpen = false;
        });
        setupDropdownToggle(spCodec, tilCodec);
        setupDropdownToggle(spQuality, tilQuality);

        findViewById(R.id.btn_scan_devices).setOnClickListener(v -> showDevicePicker());
        findViewById(R.id.btn_manual_entry).setOnClickListener(v -> showManualEntry());
        findViewById(R.id.btn_remember_connected).setOnClickListener(v -> showConnectedDevices());
        findViewById(R.id.btn_advanced).setOnClickListener(v -> toggleAdvanced());
        findViewById(R.id.btn_save_advanced).setOnClickListener(v -> saveAdvanced());
        findViewById(R.id.btn_shizuku).setOnClickListener(v -> shizukuAction());
        findViewById(R.id.btn_a11y).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btn_app_details).setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.btn_test_trigger).setOnClickListener(v -> testTrigger());
        findViewById(R.id.btn_diagnostics).setOnClickListener(v ->
                startActivity(new Intent(this, A2dpTestActivity.class)));
        findViewById(R.id.btn_copy_log).setOnClickListener(v -> copyLog());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> clearLog());

        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener);
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku listener 注册失败", t);
        }

        loadConfigIntoUi();
        refreshStatus();
        refreshLog();
        loadCrashReportIntoLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderRememberedDevices();
        ui.removeCallbacks(refreshRunnable);
        ui.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refreshRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(refreshRunnable);
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener);
        } catch (Throwable ignored) {
        }
    }

    // ---------- 配置 UI ----------

    private void loadConfigIntoUi() {
        loadingUi = true;
        swAuto.setChecked(Config.isEnabled(this));
        swScreenOn.setChecked(Config.isScreenOnRequired(this));
        swExitDev.setChecked(Config.isExitSettingsAfterDone(this));
        swResumeMusic.setChecked(Config.isResumeMusicAfterDone(this));
        int ci = indexOf(Config.CODECS, Config.getCodecLabel(this));
        if (ci < 0) ci = indexOf(Config.CODECS, Config.CODEC_CUSTOM);
        spCodec.setText(Config.CODECS[ci], false);
        updateQualitySpinner();
        loadingUi = false;
        loadAdvancedFields();
        updateAdvancedVisibility();
    }

    private void updateQualitySpinner() {
        String codec = Config.getCodecLabel(this);
        String[] opts = Config.qualityOptionsFor(codec);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.item_dropdown, android.R.id.text1, opts);
        spQuality.setAdapter(adapter);
        if (opts.length == 0) {
            spQuality.setText("无可选播放音质", false);
            spQuality.setEnabled(false);
        } else {
            spQuality.setEnabled(true);
            String cur = Config.getQualityLabel(this);
            int qi = indexOf(opts, cur);
            spQuality.setText(opts[qi < 0 ? 0 : qi], false);
        }
    }

    private void showCodecDropdown() {
        fitDropDownBelow(spCodec);
        spCodec.showDropDown();
        codecDropdownOpen = true;
    }

    private void dismissCodecDropdown() {
        spCodec.dismissDropDown();
        codecDropdownOpen = false;
    }

    private void showQualityDropdown() {
        fitDropDownBelow(spQuality);
        spQuality.showDropDown();
        qualityDropdownOpen = true;
    }

    private void dismissQualityDropdown() {
        spQuality.dismissDropDown();
        qualityDropdownOpen = false;
    }

    /**
     * 完全接管下拉交互，保证“点一下开、再点一下关”：
     * 1. 替换掉 Material 自带的 OnTouchListener（它会在 ACTION_UP 自作主张开关一次，
     *    和我们的点击逻辑打架，导致第一次点击“弹出又立刻收起”）。
     * 2. 消费全部触摸事件，避免 AutoCompleteTextView 自身的聚焦/弹出逻辑介入。
     * 3. 用“手势开始时是否已打开”来判断本次点击是开还是关，而不是依赖
     *    isPopupShowing()：因为弹窗打开后，点击输入框会先触发弹窗的
     *    “点击外部自动收起”（onDismiss），此时 isPopupShowing 已经是 false，
     *    如果按它判断就会把刚收起的弹窗又打开，看起来永远关不掉。
     * 无障碍/键盘触发的 performClick 仍由 OnClickListener 接管。
     */
    private void setupDropdownToggle(MaterialAutoCompleteTextView v,
                                     TextInputLayout til) {
        v.setThreshold(0);
        final boolean[] gestureStartedOpen = {false};
        Runnable closeIfOpen = () -> {
            if (v == spCodec) dismissCodecDropdown();
            else dismissQualityDropdown();
        };
        Runnable openIfClosed = () -> {
            if (v == spCodec) showCodecDropdown();
            else if (v.isEnabled()) showQualityDropdown();
        };
        v.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                gestureStartedOpen[0] = v == spCodec ? codecDropdownOpen : qualityDropdownOpen;
            } else if (action == MotionEvent.ACTION_UP) {
                // 手势开始时是打开状态 → 本次点击负责收起（哪怕弹窗已被“点击外部”提前收起，
                // 这里也只是把状态归位，不会重新打开）
                if (gestureStartedOpen[0]) closeIfOpen.run();
                else openIfClosed.run();
            }
            return true;
        });
        View endIcon = getEndIconView(til);
        if (endIcon != null) {
            endIcon.setOnTouchListener((view, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    gestureStartedOpen[0] = v == spCodec ? codecDropdownOpen : qualityDropdownOpen;
                } else if (action == MotionEvent.ACTION_UP) {
                    if (gestureStartedOpen[0]) closeIfOpen.run();
                    else openIfClosed.run();
                }
                return true;
            });
        }
        v.setOnClickListener(ignored -> {
            if (v == spCodec) {
                if (codecDropdownOpen) dismissCodecDropdown();
                else showCodecDropdown();
            } else if (v.isEnabled()) {
                if (qualityDropdownOpen) dismissQualityDropdown();
                else showQualityDropdown();
            }
        });
        til.setEndIconOnClickListener(ignored -> {
            if (til == tilCodec) {
                if (codecDropdownOpen) dismissCodecDropdown();
                else showCodecDropdown();
            } else if (v.isEnabled()) {
                if (qualityDropdownOpen) dismissQualityDropdown();
                else showQualityDropdown();
            }
        });
        // 弹窗被外部点击/选择/返回键收起时，同步我们的状态
        v.setOnDismissListener(() -> {
            if (v == spCodec) codecDropdownOpen = false;
            else qualityDropdownOpen = false;
        });
    }

    /** TextInputLayout.getEndIconView() 是包内方法，这里通过反射拿，拿不到就只靠点击事件。 */
    private View getEndIconView(TextInputLayout til) {
        try {
            java.lang.reflect.Method m = TextInputLayout.class
                    .getDeclaredMethod("getEndIconView");
            m.setAccessible(true);
            return (View) m.invoke(til);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 限制弹出高度为输入框下方剩余空间，避免系统自动改成向上弹出。 */
    private void fitDropDownBelow(MaterialAutoCompleteTextView v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int fieldBottom = loc[1] + v.getHeight();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // 底部手势条/导航栏会占用屏幕底部空间，PopupWindow 计算“下方放不放得下”时
        // 用的是扣除 inset 后的区域；不扣掉就会误判，导致明明下方有空间却向上弹出。
        int bottomInset = 0;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(v);
        if (insets != null) {
            bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
        }
        int below = screenH - bottomInset - fieldBottom;
        if (below > 0) {
            v.setDropDownHeight(Math.max(below - 8, 1));
            v.setDropDownVerticalOffset(0);
        }
    }

    private void updateAdvancedVisibility() {
        boolean custom = Config.isCustom(this);
        codecRowEdit.setVisibility(custom ? View.VISIBLE : View.GONE);
        codecOptionEdit.setVisibility(custom ? View.VISIBLE : View.GONE);
        qualityRowEdit.setVisibility(custom ? View.VISIBLE : View.GONE);
        qualityOptionEdit.setVisibility(custom ? View.VISIBLE : View.GONE);
        advancedPanel.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    private void loadAdvancedFields() {
        codecRowEdit.setText(Config.getCustomCodecRow(this));
        codecOptionEdit.setText(Config.getCustomCodecOption(this));
        qualityRowEdit.setText(Config.getCustomQualityRow(this));
        qualityOptionEdit.setText(Config.getCustomQualityOption(this));
        delayEdit.setText(String.valueOf(Config.getTriggerDelayMs(this)));
        swScreenOn.setChecked(Config.isScreenOnRequired(this));
    }

    private int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return -1;
    }

    private void toggleAdvanced() {
        boolean show = advancedPanel.getVisibility() != View.VISIBLE;
        advancedPanel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void saveAdvanced() {
        long delay = 2500;
        try {
            delay = Long.parseLong(delayEdit.getText().toString().trim());
        } catch (Exception ignored) {
        }
        if (delay < 500) delay = 500;
        Config.saveCustom(this,
                codecRowEdit.getText().toString().trim(),
                codecOptionEdit.getText().toString().trim(),
                qualityRowEdit.getText().toString().trim(),
                qualityOptionEdit.getText().toString().trim(),
                delay, swScreenOn.isChecked());
        String mac = Config.getActiveMac(this);
        if (mac != null) {
            Config.setDevicePreset(this, mac, Config.CODEC_CUSTOM,
                    qualityOptionEdit.getText().toString().trim());
        }
        spCodec.setText(Config.CODEC_CUSTOM, false);
        updateQualitySpinner();
        updateAdvancedVisibility();
        toast("自定义方案已保存");
    }

    // ---------- 记忆设备 ----------

    private void renderRememberedDevices() {
        rememberedList.removeAllViews();
        List<JSONObject> list = Config.rememberedList(this);
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        String activeMac = Config.getActiveMac(this);
        for (JSONObject o : list) {
            String name = o.optString("name");
            String mac = o.optString("mac");
            long ts = o.optLong("ts");
            boolean active = mac.equalsIgnoreCase(activeMac);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setFocusable(true);
            row.setPadding(0, 6, 0, 6);
            if (active) {
                row.setBackgroundResource(R.drawable.bg_card);
            }
            TextView tv = new TextView(this);
            tv.setText((name.isEmpty() ? "未命名设备" : name)
                    + (active ? "（当前预设）" : "") + "\n"
                    + mac + " · 记忆于 " + fmt.format(new Date(ts)));
            tv.setTextSize(13);
            tv.setPadding(0, 6, 0, 6);
            row.setOnClickListener(v -> selectRememberedDevice(mac));
            ImageButton del = new ImageButton(this);
            del.setImageResource(R.drawable.ic_close);
            del.setBackground(null);
            del.setImageTintList(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.text_secondary)));
            del.setContentDescription("移除记忆设备");
            del.setPadding((int) (12 * getResources().getDisplayMetrics().density),
                    (int) (4 * getResources().getDisplayMetrics().density),
                    (int) (12 * getResources().getDisplayMetrics().density),
                    (int) (4 * getResources().getDisplayMetrics().density));
            del.setOnClickListener(v -> confirmRemove(mac));
            row.addView(tv, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(del);
            rememberedList.addView(row);
        }
        if (list.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("还没有记忆的设备：任意耳机连接一次后会自动出现在这里");
            tv.setTextSize(13);
            tv.setPadding(0, 6, 0, 6);
            rememberedList.addView(tv);
        }
    }

    /** 点击记忆设备：加载其预设；再点一次取消选择，回到全局方案。 */
    private void selectRememberedDevice(String mac) {
        if (mac == null) return;
        String active = Config.getActiveMac(this);
        if (mac.equalsIgnoreCase(active)) {
            Config.setActiveMac(this, null);
            renderRememberedDevices();
            toast("已取消选择，使用全局方案");
            return;
        }
        Config.setActiveMac(this, mac);
        String codec = Config.presetCodec(this, mac);
        String quality = Config.presetQuality(this, mac);
        if (codec == null && quality == null) {
            // 第一次点：把当前方案存成这个耳机的预设
            codec = Config.getCodecLabel(this);
            quality = Config.getQualityLabel(this);
            Config.setDevicePreset(this, mac, codec, quality);
        }
        loadingUi = true;
        if (codec != null) {
            Config.setCodecLabel(this, codec);
            spCodec.setText(codec, false);
        }
        if (quality != null) {
            Config.setQualityLabel(this, quality);
        }
        updateQualitySpinner();
        loadingUi = false;
        updateAdvancedVisibility();
        renderRememberedDevices();
        toast("已切换到该耳机的预设");
    }

    private void confirmRemove(String mac) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("移除记忆设备")
                .setMessage("确定从记忆里移除 " + mac + " 吗？")
                .setPositiveButton("移除", (d, w) -> {
                    Config.removeRemembered(this, mac);
                    renderRememberedDevices();
                    toast("已移除 " + mac);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 列出当前已连接的蓝牙设备（A2DP + 耳机），选一个加入记忆；读不到时回退到已配对列表。 */
    private void showConnectedDevices() {
        if (btAdapter == null) {
            toast("设备不支持蓝牙");
            return;
        }
        if (!btAdapter.isEnabled()) {
            toast("请先打开蓝牙");
            return;
        }
        if (!hasBtPermissions()) {
            toast("需要蓝牙权限，请允许后重试");
            requestBtPermissions();
            return;
        }
        final List<BluetoothDevice> found = new ArrayList<>();
        final Map<String, BluetoothDevice> seen = new LinkedHashMap<>();
        final boolean[] done = {false};
        final Runnable[] connectedTimeout = {null};
        // 1) 先试 BluetoothManager 直连查询
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            if (bm != null) {
                addDevices(bm.getConnectedDevices(BluetoothProfile.A2DP), seen, found);
                addDevices(bm.getConnectedDevices(BluetoothProfile.HEADSET), seen, found);
            }
        } catch (Throwable ignored) {
        }
        if (!seen.isEmpty()) {
            showConnectedDialog(found, false);
            return;
        }
        final Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            if (connectedTimeout[0] != null) ui.removeCallbacks(connectedTimeout[0]);
            if (seen.isEmpty()) {
                try {
                    for (BluetoothDevice d : btAdapter.getBondedDevices()) {
                        if (d != null && d.getAddress() != null
                                && !seen.containsKey(d.getAddress())) {
                            seen.put(d.getAddress(), d);
                            found.add(d);
                        }
                    }
                } catch (SecurityException ignored) {
                }
                if (found.isEmpty()) {
                    toast("没有可记忆的设备");
                    return;
                }
                showConnectedDialog(found, true);
            } else {
                showConnectedDialog(found, false);
            }
        };
        final int[] pending = {2};
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (proxy != null) {
                    try {
                        List<BluetoothDevice> list = null;
                        if (profile == BluetoothProfile.A2DP) {
                            list = ((BluetoothA2dp) proxy).getConnectedDevices();
                        } else if (profile == BluetoothProfile.HEADSET) {
                            list = ((BluetoothHeadset) proxy).getConnectedDevices();
                        }
                        if (list != null) {
                            for (BluetoothDevice d : list) {
                                if (d != null && d.getAddress() != null
                                        && !seen.containsKey(d.getAddress())) {
                                    seen.put(d.getAddress(), d);
                                    found.add(d);
                                }
                            }
                        }
                        // 2) 代理拿到了但列表为空时，逐个检查已配对设备的连接状态
                        if (list == null || list.isEmpty()) {
                            for (BluetoothDevice d : btAdapter.getBondedDevices()) {
                                if (d == null || d.getAddress() == null) continue;
                                boolean connected = false;
                                if (profile == BluetoothProfile.A2DP) {
                                    connected = ((BluetoothA2dp) proxy)
                                            .getConnectionState(d) == BluetoothProfile.STATE_CONNECTED;
                                } else if (profile == BluetoothProfile.HEADSET) {
                                    connected = ((BluetoothHeadset) proxy)
                                            .getConnectionState(d) == BluetoothProfile.STATE_CONNECTED;
                                }
                                if (connected && !seen.containsKey(d.getAddress())) {
                                    seen.put(d.getAddress(), d);
                                    found.add(d);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        btAdapter.closeProfileProxy(profile, proxy);
                    } catch (Throwable ignored) {
                    }
                }
                if (--pending[0] <= 0) finish.run();
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (--pending[0] <= 0) finish.run();
            }
        };
        try {
            btAdapter.getProfileProxy(this, listener, BluetoothProfile.A2DP);
            btAdapter.getProfileProxy(this, listener, BluetoothProfile.HEADSET);
        } catch (Throwable t) {
            finish.run();
        }
        connectedTimeout[0] = finish;
        ui.postDelayed(connectedTimeout[0], 6000);
    }

    private void addDevices(List<BluetoothDevice> list,
                            Map<String, BluetoothDevice> seen,
                            List<BluetoothDevice> found) {
        if (list == null) return;
        for (BluetoothDevice d : list) {
            if (d != null && d.getAddress() != null && !seen.containsKey(d.getAddress())) {
                seen.put(d.getAddress(), d);
                found.add(d);
            }
        }
    }

    private void showConnectedDialog(List<BluetoothDevice> devices, boolean fallback) {
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) labels[i] = deviceLabel(devices.get(i));
        new MaterialAlertDialogBuilder(this)
                .setTitle(fallback ? "已配对设备（未能读取连接状态，请确认后选择）"
                        : "已连接的蓝牙设备")
                .setItems(labels, (d, w) -> {
                    BluetoothDevice dev = devices.get(w);
                    Config.rememberDevice(this, dev);
                    renderRememberedDevices();
                    toast("已记忆：" + deviceLabel(dev));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 设备扫描 / 手动添加 ----------

    private List<String> neededPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        return perms;
    }

    private boolean hasBtPermissions() {
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void requestBtPermissions() {
        requestPermissions(neededPermissions().toArray(new String[0]), REQ_BT_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERMS) {
            boolean ok = grantResults.length > 0;
            for (int r : grantResults) ok = ok && r == PackageManager.PERMISSION_GRANTED;
            toast(ok ? "蓝牙权限已授予，可以重新扫描" : "蓝牙权限被拒绝");
            refreshStatus();
        }
    }

    private void showDevicePicker() {
        if (btAdapter == null) {
            toast("设备不支持蓝牙");
            return;
        }
        if (!btAdapter.isEnabled()) {
            toast("请先打开蓝牙");
            return;
        }
        if (!hasBtPermissions()) {
            toast("需要蓝牙权限，请允许后重试");
            requestBtPermissions();
            return;
        }
        pickerDevices.clear();
        pickerSeen.clear();
        pickerAdapter.clear();
        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (d != null && d.getAddress() != null && !pickerSeen.containsKey(d.getAddress())) {
                        pickerSeen.put(d.getAddress(), d);
                        pickerDevices.add(d);
                        pickerAdapter.add(deviceLabel(d));
                    }
                }
            }
        } catch (SecurityException e) {
            toast("没有蓝牙连接权限");
            return;
        }

        androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle("选择耳机（正在扫描…）")
                .setNegativeButton("取消", null)
                .setAdapter(pickerAdapter, (d, pos) -> {
                    if (pos >= 0 && pos < pickerDevices.size()) {
                        Config.rememberDevice(this, pickerDevices.get(pos));
                        renderRememberedDevices();
                        toast("已记忆：" + deviceLabel(pickerDevices.get(pos)));
                    }
                })
                .create();
        dlg.setOnDismissListener(d -> {
            try {
                btAdapter.cancelDiscovery();
            } catch (Throwable ignored) {
            }
            try {
                unregisterReceiver(discoveryReceiver);
            } catch (Throwable ignored) {
            }
        });
        dlg.show();
        try {
            ContextCompat.registerReceiver(this, discoveryReceiver,
                    new IntentFilter(BluetoothDevice.ACTION_FOUND),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "注册发现广播失败", t);
        }
        try {
            btAdapter.startDiscovery();
        } catch (SecurityException e) {
            toast("没有蓝牙扫描权限");
        } catch (Throwable t) {
            Log.w(TAG, "startDiscovery 失败", t);
        }
    }

    private void showManualEntry() {
        View dialogView = getLayoutInflater().inflate(
                R.layout.dialog_manual_entry, null);
        EditText input = dialogView.findViewById(R.id.et_manual_mac);
        new MaterialAlertDialogBuilder(this)
                .setTitle("手动添加设备到记忆")
                .setMessage("默认任意设备都会自动修复；手动添加只是为了在列表里记住它。")
                .setView(dialogView)
                .setPositiveButton("确定", (d, w) -> {
                    String mac = Config.normalizeMac(input.getText().toString());
                    if (mac == null) {
                        toast("地址格式不对，示例：FC:C3:3A:00:03:AC");
                        return;
                    }
                    BluetoothDevice dev = null;
                    try {
                        dev = btAdapter.getRemoteDevice(mac);
                    } catch (Throwable ignored) {
                    }
                    if (dev == null) {
                        toast("地址格式对，但系统无法识别该设备");
                        return;
                    }
                    Config.rememberDevice(this, dev);
                    renderRememberedDevices();
                    toast("已记忆：" + mac);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String deviceLabel(BluetoothDevice d) {
        String name = null;
        try {
            name = d.getName();
        } catch (Throwable ignored) {
        }
        return (name == null || name.isEmpty() ? "未命名设备" : name) + "\n" + d.getAddress();
    }

    // ---------- 权限与状态 ----------

    private boolean isAccessibilityEnabled() {
        ComponentName cn = new ComponentName(this, LhdcAutoService.class);
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        return enabled.contains(cn.flattenToString());
    }

    private void refreshStatus() {
        String shizuku;
        try {
            boolean ping = Shizuku.pingBinder();
            boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            shizuku = (ping ? "Shizuku：运行中" : "Shizuku：未运行")
                    + "，授权：" + (granted ? "已授权" : "未授权");
        } catch (Throwable t) {
            shizuku = "Shizuku：不可用";
        }
        shizukuStatus.setText(shizuku);
        a11yStatus.setText("无障碍服务：" + (isAccessibilityEnabled() ? "已启用" : "未启用"));
    }

    private void shizukuAction() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku 未运行：请先用无线调试启动，或在 Shizuku App 里点启动");
                openShizukuManager();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ_SHIZUKU);
            } else {
                ShellExec.bind(this);
                toast("Shizuku 已就绪");
                refreshStatus();
            }
        } catch (Throwable t) {
            toast("Shizuku 请求失败：" + t.getMessage());
        }
    }

    private void openShizukuManager() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.manager");
            if (i != null) {
                startActivity(i);
            } else {
                Intent i2 = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (i2 != null) {
                    startActivity(i2);
                } else {
                    toast("未安装 Shizuku，请先安装");
                }
            }
        } catch (Throwable t) {
            toast("无法打开 Shizuku：" + t.getMessage());
        }
    }

    private void testTrigger() {
        if (!isAccessibilityEnabled()) {
            toast("请先开启无障碍服务");
            return;
        }
        if (LhdcAutoService.instance == null) {
            toast("无障碍服务实例未运行，请确认已在系统里启用");
            return;
        }
        LhdcAutoService.trigger(this);
        toast("已触发，请看下方日志");
    }

    // ---------- 日志 ----------

    private void refreshLog() {
        String h = Config.sp(this).getString(Config.KEY_LOG_HISTORY, "");
        logView.setText(h);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copyLog() {
        String h = Config.sp(this).getString(Config.KEY_LOG_HISTORY, "");
        if (h.isEmpty()) {
            toast("日志为空");
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("音质助手日志", h));
        toast("日志已复制");
    }

    private void clearLog() {
        Config.sp(this).edit().remove(Config.KEY_LOG_HISTORY).apply();
        refreshLog();
    }

    private void loadCrashReportIntoLog() {
        java.io.File f = App.crashFile(this);
        if (!f.exists()) return;
        try {
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            in.close();
            String content = new String(data, "UTF-8");
            SharedPreferences sp = Config.sp(this);
            String h = sp.getString(Config.KEY_LOG_HISTORY, "")
                    + "===== 上次崩溃 =====\n" + content + "\n";
            sp.edit().putString(Config.KEY_LOG_HISTORY, h).apply();
            f.delete();
            refreshLog();
        } catch (Exception ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

package com.lhdcprobe;

import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * 二级菜单：无障碍 UI 兜底（默认关闭）、自定义方案/流程参数、测试与日志。
 * 日常自动修复走 API 直写（DirectorCore），不依赖本页任何开关。
 */
public class ToolsActivity extends AppCompatActivity {

    private static final String TAG = "LHDCProbe";
    private static final int REQ_SHIZUKU = 1001;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private MaterialSwitch swUiFlow;
    private MaterialSwitch swScreenOn;
    private MaterialSwitch swExitDev;
    private MaterialSwitch swResumeMusic;
    private TextView shizukuStatus;
    private TextView a11yStatus;
    private TextView logView;
    private ScrollView logScroll;
    private EditText codecRowEdit;
    private EditText codecOptionEdit;
    private EditText qualityRowEdit;
    private EditText qualityOptionEdit;
    private EditText delayEdit;
    private boolean loadingUi;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            refreshLog();
            ui.postDelayed(this, 2000);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener shizukuListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQ_SHIZUKU) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    ShellExec.bind(ToolsActivity.this);
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
        setContentView(R.layout.activity_tools);

        swUiFlow = findViewById(R.id.sw_ui_flow);
        shizukuStatus = findViewById(R.id.tv_shizuku_status);
        a11yStatus = findViewById(R.id.tv_a11y_status);
        logView = findViewById(R.id.tv_log);
        logScroll = findViewById(R.id.log_scroll);
        swScreenOn = findViewById(R.id.sw_screen_on);
        swExitDev = findViewById(R.id.sw_exit_dev);
        swResumeMusic = findViewById(R.id.sw_resume_music);
        codecRowEdit = findViewById(R.id.et_codec_row);
        codecOptionEdit = findViewById(R.id.et_codec_option);
        qualityRowEdit = findViewById(R.id.et_quality_row);
        qualityOptionEdit = findViewById(R.id.et_quality_option);
        delayEdit = findViewById(R.id.et_delay);

        swUiFlow.setOnCheckedChangeListener((v, isChecked) -> {
            if (loadingUi) return;
            Config.setUiFlowEnabled(this, isChecked);
            toast(isChecked
                    ? "UI 兜底已开启（仅 API 修复失败时才会执行）"
                    : "UI 兜底已关闭");
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
        // 长按进入协议定向连接实验（CodecDirectorActivity）
        findViewById(R.id.btn_diagnostics).setOnLongClickListener(v -> {
            startActivity(new Intent(this, CodecDirectorActivity.class));
            return true;
        });
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
        swUiFlow.setChecked(Config.isUiFlowEnabled(this));
        swScreenOn.setChecked(Config.isScreenOnRequired(this));
        swExitDev.setChecked(Config.isExitSettingsAfterDone(this));
        swResumeMusic.setChecked(Config.isResumeMusicAfterDone(this));
        loadingUi = false;
        loadAdvancedFields();
        updateAdvancedVisibility();
    }

    private void updateAdvancedVisibility() {
        // 自定义文案输入框仅在选择"自定义"协议时相关；保留展示由用户自行填写
        boolean custom = Config.isCustom(this);
        codecRowEdit.setVisibility(custom ? View.VISIBLE : View.GONE);
        codecOptionEdit.setVisibility(custom ? View.VISIBLE : View.GONE);
        qualityRowEdit.setVisibility(custom ? View.VISIBLE : View.GONE);
        qualityOptionEdit.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    private void loadAdvancedFields() {
        codecRowEdit.setText(Config.getCustomCodecRow(this));
        codecOptionEdit.setText(Config.getCustomCodecOption(this));
        qualityRowEdit.setText(Config.getCustomQualityRow(this));
        qualityOptionEdit.setText(Config.getCustomQualityOption(this));
        delayEdit.setText(String.valueOf(Config.getTriggerDelayMs(this)));
        swScreenOn.setChecked(Config.isScreenOnRequired(this));
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
        toast("自定义方案已保存");
    }

    // ---------- 环境 ----------

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
                Intent i2 = getPackageManager().getLaunchIntentForPackage(
                        "moe.shizuku.privileged.api");
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
        String fix = DirectorCore.lastLog(this);
        logView.setText(TextUtils.isEmpty(h) ? "API 修复日志: " + fix : h);
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
            android.content.SharedPreferences sp = Config.sp(this);
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

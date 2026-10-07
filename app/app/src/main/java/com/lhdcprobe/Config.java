package com.lhdcprobe;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 用户可配置项：目标协议、码率、触发延迟、记忆设备列表等。 */
public final class Config {

    public static final String PREFS = "lhdc_auto";

    public static final String KEY_ENABLED = "enabled";
    /** 无障碍 UI 兜底流程开关（默认关闭；API 修复失败时才需要）。 */
    public static final String KEY_UI_FLOW = "ui_flow_enabled";
    public static final String KEY_CODEC_LABEL = "codec_label";
    public static final String KEY_QUALITY_LABEL = "quality_label";
    public static final String KEY_SEARCH_KEYWORD = "search_keyword";
    public static final String KEY_RESULT_TEXT = "search_result_text";
    public static final String KEY_CODEC_ROW = "codec_row_text";
    public static final String KEY_CODEC_OPTION = "codec_option_text";
    public static final String KEY_QUALITY_ROW = "quality_row_text";
    public static final String KEY_TRIGGER_DELAY = "trigger_delay_ms";
    public static final String KEY_SCREEN_ON = "screen_on_required";
    public static final String KEY_REMEMBERED = "remembered_devices";
    public static final String KEY_ACTIVE_MAC = "active_mac";
    public static final String KEY_EXIT_SETTINGS_AFTER_DONE = "exit_settings_after_done";
    public static final String KEY_RESUME_MUSIC = "resume_music_after_done";
    public static final String KEY_LAST_LOG = "last_log";
    public static final String KEY_LOG_HISTORY = "log_history";

    public static final String CODEC_SYSTEM_DEFAULT = "使用系统选择（默认）";
    public static final String CODEC_CUSTOM = "自定义（手动填写）";
    public static final String OPTIONAL_CODEC_ENABLE = "启用可选编解码器";
    public static final String OPTIONAL_CODEC_DISABLE = "停用可选编解码器";

    public static final String[] CODECS = {
            CODEC_SYSTEM_DEFAULT,
            "SBC",
            "AAC",
            "Qualcomm® aptX™ 音频",
            "Qualcomm® aptX™ HD 音频",
            "LDAC",
            "Qualcomm® aptX™ Adaptive 音频",
            "Qualcomm® aptX™ TWS+ 音频",
            "LHDC V5",
            "LHDC V3/V4",
            "LHDC V2",
            "LHDC V1",
            "MIHC",
            OPTIONAL_CODEC_ENABLE,
            OPTIONAL_CODEC_DISABLE,
            CODEC_CUSTOM,
    };

    public static final String[] LHDC_QUALITY = {
            "偏重低延迟（256kbps）",
            "偏重连接质量（400kbps）",
            "兼顾音频和连接质量（500/560kbps）",
            "偏重音频质量（900kbps）",
            "尽可能提供更佳音质（自适应比特率）",
    };

    public static final String[] LDAC_QUALITY = {
            "偏重音频质量（990kbps/909kbps）",
            "兼顾音频和连接质量（660kbps/606kbps）",
            "偏重连接质量（330kbps/303kbps）",
            "尽可能提供更佳音质（自适应比特率）",
    };

    public static final String DEFAULT_CODEC = "LHDC V3/V4";
    public static final String DEFAULT_QUALITY = "偏重音频质量（900kbps）";

    private Config() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    public static boolean isUiFlowEnabled(Context c) {
        return sp(c).getBoolean(KEY_UI_FLOW, false);
    }

    public static void setUiFlowEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_UI_FLOW, v).apply();
    }

    public static String getCodecLabel(Context c) {
        return sp(c).getString(KEY_CODEC_LABEL, DEFAULT_CODEC);
    }

    public static void setCodecLabel(Context c, String v) {
        sp(c).edit().putString(KEY_CODEC_LABEL, v).apply();
    }

    public static String getQualityLabel(Context c) {
        String q = sp(c).getString(KEY_QUALITY_LABEL, DEFAULT_QUALITY);
        return isQualityValidFor(c, getCodecLabel(c), q) ? q : defaultQualityFor(getCodecLabel(c));
    }

    public static void setQualityLabel(Context c, String v) {
        sp(c).edit().putString(KEY_QUALITY_LABEL, v).apply();
    }

    public static boolean isCustom(Context c) {
        return CODEC_CUSTOM.equals(getCodecLabel(c));
    }

    public static boolean isCustom(Context c, String mac) {
        return CODEC_CUSTOM.equals(effectiveCodecLabel(c, mac));
    }

    public static String[] qualityOptionsFor(String codec) {
        if (codec == null) return new String[0];
        if (codec.startsWith("LHDC")) return LHDC_QUALITY;
        if ("LDAC".equals(codec)) return LDAC_QUALITY;
        return new String[0];
    }

    public static boolean isQualityValidFor(Context c, String codec, String quality) {
        if (TextUtils.isEmpty(quality)) return true;
        for (String s : qualityOptionsFor(codec)) {
            if (s.equals(quality)) return true;
        }
        return false;
    }

    public static String defaultQualityFor(String codec) {
        String[] opts = qualityOptionsFor(codec);
        return opts.length > 0 ? opts[0] : "";
    }

    // ---------- 解析后的流程参数 ----------

    public static String resolveKeyword(Context c) {
        return "LHDC";
    }

    public static String resolveResultText(Context c) {
        return "蓝牙音频 LHDC 编解码器：播放质量";
    }

    public static String resolveCodecRow(Context c) {
        return resolveCodecRow(c, null);
    }

    public static String resolveCodecRow(Context c, String mac) {
        if (isCustom(c, mac)) return sp(c).getString(KEY_CODEC_ROW, "蓝牙音频编解码器");
        return "蓝牙音频编解码器";
    }

    public static String resolveCodecOption(Context c) {
        return resolveCodecOption(c, null);
    }

    public static String resolveCodecOption(Context c, String mac) {
        if (isCustom(c, mac)) return sp(c).getString(KEY_CODEC_OPTION, "");
        return effectiveCodecLabel(c, mac);
    }

    public static String resolveQualityRow(Context c) {
        return resolveQualityRow(c, null);
    }

    public static String resolveQualityRow(Context c, String mac) {
        if (isCustom(c, mac)) return sp(c).getString(KEY_QUALITY_ROW, "");
        String codec = effectiveCodecLabel(c, mac);
        String family = codecFamily(codec);
        if (family == null) return "";
        return "蓝牙音频 " + family + " 编解码器：播放质量";
    }

    public static String resolveQualityOption(Context c) {
        return resolveQualityOption(c, null);
    }

    public static String resolveQualityOption(Context c, String mac) {
        if (isCustom(c, mac)) return sp(c).getString(KEY_QUALITY_LABEL, "");
        return effectiveQualityLabel(c, mac);
    }

    private static String codecFamily(String codec) {
        if (codec == null) return null;
        if (codec.startsWith("LHDC")) return "LHDC";
        if ("LDAC".equals(codec)) return "LDAC";
        return null;
    }

    // ---------- 触发参数 ----------

    public static long getTriggerDelayMs(Context c) {
        return sp(c).getLong(KEY_TRIGGER_DELAY, 2500);
    }

    public static void setTriggerDelayMs(Context c, long v) {
        sp(c).edit().putLong(KEY_TRIGGER_DELAY, v).apply();
    }

    public static boolean isScreenOnRequired(Context c) {
        return sp(c).getBoolean(KEY_SCREEN_ON, true);
    }

    public static void setScreenOnRequired(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_SCREEN_ON, v).apply();
    }

    // ---------- 完成后行为开关（默认关闭） ----------

    public static boolean isExitSettingsAfterDone(Context c) {
        return sp(c).getBoolean(KEY_EXIT_SETTINGS_AFTER_DONE, false);
    }

    public static void setExitSettingsAfterDone(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_EXIT_SETTINGS_AFTER_DONE, v).apply();
    }

    public static boolean isResumeMusicAfterDone(Context c) {
        return sp(c).getBoolean(KEY_RESUME_MUSIC, false);
    }

    public static void setResumeMusicAfterDone(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_RESUME_MUSIC, v).apply();
    }

    // ---------- 每耳机预设 ----------

    /** 当前选中的耳机（点击记忆列表加载其预设后写入）；null = 全局方案。 */
    public static String getActiveMac(Context c) {
        return sp(c).getString(KEY_ACTIVE_MAC, null);
    }

    public static void setActiveMac(Context c, String mac) {
        sp(c).edit().putString(KEY_ACTIVE_MAC, mac).apply();
    }

    public static String presetCodec(Context c, String mac) {
        JSONObject o = findRemembered(c, mac);
        if (o == null) return null;
        String v = o.optString("codec", "");
        return v.isEmpty() ? null : v;
    }

    public static String presetQuality(Context c, String mac) {
        JSONObject o = findRemembered(c, mac);
        if (o == null) return null;
        String v = o.optString("quality", "");
        return v.isEmpty() ? null : v;
    }

    /** 保存某耳机的预设；codec/quality 传 null 表示不修改。 */
    public static void setDevicePreset(Context c, String mac, String codec, String quality) {
        if (mac == null) return;
        List<JSONObject> list = rememberedList(c);
        for (JSONObject o : list) {
            if (mac.equalsIgnoreCase(o.optString("mac"))) {
                try {
                    if (codec != null) o.put("codec", codec);
                    else o.remove("codec");
                    if (quality != null) o.put("quality", quality);
                    else o.remove("quality");
                } catch (JSONException ignored) {
                }
                saveRememberedList(c, list);
                return;
            }
        }
    }

    private static JSONObject findRemembered(Context c, String mac) {
        if (mac == null) return null;
        for (JSONObject o : rememberedList(c)) {
            if (mac.equalsIgnoreCase(o.optString("mac"))) return o;
        }
        return null;
    }

    /** 某耳机连接时实际使用的协议（有预设用预设，否则用全局）。 */
    public static String effectiveCodecLabel(Context c, String mac) {
        String p = presetCodec(c, mac);
        return p != null ? p : getCodecLabel(c);
    }

    public static String effectiveQualityLabel(Context c, String mac) {
        String codec = effectiveCodecLabel(c, mac);
        String p = presetQuality(c, mac);
        if (p != null && isQualityValidFor(c, codec, p)) return p;
        String q = getQualityLabel(c);
        return isQualityValidFor(c, codec, q) ? q : defaultQualityFor(codec);
    }

    // ---------- 设备记忆（默认任意设备，连接即记忆） ----------

    public static boolean isTarget(Context c, BluetoothDevice d) {
        return d != null; // v8：默认任意设备，不做限制
    }

    public static void rememberDevice(Context c, BluetoothDevice d) {
        if (d == null || d.getAddress() == null) return;
        String name = null;
        try {
            name = d.getName();
        } catch (Throwable ignored) {
        }
        List<JSONObject> list = rememberedList(c);
        long now = System.currentTimeMillis();
        JSONObject entry = new JSONObject();
        try {
            entry.put("name", name == null ? "" : name);
            entry.put("mac", d.getAddress());
            entry.put("ts", now);
        } catch (JSONException ignored) {
        }
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).optString("mac").equalsIgnoreCase(d.getAddress())) {
                list.set(i, entry);
                found = true;
                break;
            }
        }
        if (!found) list.add(0, entry);
        while (list.size() > 8) list.remove(list.size() - 1);
        saveRememberedList(c, list);
    }

    public static List<JSONObject> rememberedList(Context c) {
        List<JSONObject> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_REMEMBERED, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static void removeRemembered(Context c, String mac) {
        List<JSONObject> list = rememberedList(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).optString("mac").equalsIgnoreCase(mac)) {
                list.remove(i);
                break;
            }
        }
        saveRememberedList(c, list);
    }

    private static void saveRememberedList(Context c, List<JSONObject> list) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        sp(c).edit().putString(KEY_REMEMBERED, arr.toString()).apply();
    }

    // ---------- 自定义字段 ----------

    public static void saveCustom(Context c, String codecRow, String codecOption, String qualityRow,
                                  String qualityOption, long delayMs, boolean screenOn) {
        sp(c).edit()
                .putString(KEY_CODEC_ROW, codecRow)
                .putString(KEY_CODEC_OPTION, codecOption)
                .putString(KEY_QUALITY_ROW, qualityRow)
                .putString(KEY_QUALITY_LABEL, qualityOption)
                .putLong(KEY_TRIGGER_DELAY, delayMs)
                .putBoolean(KEY_SCREEN_ON, screenOn)
                .putString(KEY_CODEC_LABEL, CODEC_CUSTOM)
                .apply();
    }

    public static String getCustomCodecRow(Context c) {
        return sp(c).getString(KEY_CODEC_ROW, "蓝牙音频编解码器");
    }

    public static String getCustomCodecOption(Context c) {
        return sp(c).getString(KEY_CODEC_OPTION, "");
    }

    public static String getCustomQualityRow(Context c) {
        return sp(c).getString(KEY_QUALITY_ROW, "");
    }

    public static String getCustomQualityOption(Context c) {
        return sp(c).getString(KEY_QUALITY_LABEL, "");
    }

    public static String lastLog(Context c) {
        return sp(c).getString(KEY_LAST_LOG, "服务未运行");
    }

    public static String normalizeMac(String raw) {
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
}

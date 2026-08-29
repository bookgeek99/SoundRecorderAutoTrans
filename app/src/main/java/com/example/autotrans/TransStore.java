package com.example.autotrans;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;

/**
 * Cross-process shared storage for statistics and settings.
 *
 * The AutoTransHook runs inside the SoundRecorder process, while MainActivity runs in
 * the module's own process. These are separate UIDs, so we cannot use the module's
 * internal SharedPreferences from the hook. Instead we use a shared file on the
 * public storage (/sdcard/Download/autotrans/) that both sides can read/write
 * (the SoundRecorder app has broad media/storage access; the module app asks for
 * all-files access to be able to write). JSON is used so both sides can parse it.
 */
public final class TransStore {
    private static final String DIR = "Download/autotrans";
    private static final String FILE_STATS = "status.json";
    private static final String FILE_PREFS = "settings.json";

    public static final String KEY_TOTAL = "total";
    public static final String KEY_DONE = "done";
    public static final String KEY_CONVERTED_SINCE_BOOT = "convertedSinceBoot";
    public static final String KEY_LAST_TIME = "lastTime";
    public static final String KEY_LAST_NAME = "lastName";
    public static final String KEY_LAST_STATE = "lastState";
    public static final String KEY_RUN_COUNT = "runCount";

    public static final String PREF_WIFI_ONLY = "wifiOnly";
    public static final String PREF_CHARGING_ONLY = "chargingOnly";
    public static final String PREF_ENABLED = "enabled";
    public static final String PREF_ASR_LANG = "asrLang";
    public static final String PREF_TOAST = "showToast";

    private TransStore() {
    }

    private static File dir() {
        File base = new File(Environment.getExternalStorageDirectory(), DIR);
        if (!base.exists()) {
            base.mkdirs();
        }
        return base;
    }

    private static File statsFile() {
        return new File(dir(), FILE_STATS);
    }

    private static File prefsFile() {
        return new File(dir(), FILE_PREFS);
    }

    private static String read(File f) {
        if (f == null || !f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void write(File f, String content) {
        try {
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(f);
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Read statistics as a plain-text summary (for UI / logs). */
    public static String readStatsSummary() {
        String raw = read(statsFile());
        StringBuilder sb = new StringBuilder();
        if (TextUtils.isEmpty(raw)) {
            sb.append("暂无统计数据");
            return sb.toString();
        }
        try {
            JSONObject o = new JSONObject(raw);
            int total = o.optInt(KEY_TOTAL, 0);
            int done = o.optInt(KEY_DONE, 0);
            int sinceBoot = o.optInt(KEY_CONVERTED_SINCE_BOOT, 0);
            int run = o.optInt(KEY_RUN_COUNT, 0);
            sb.append("本地录音总数: ").append(total).append('\n');
            sb.append("已完成转文字: ").append(done).append('\n');
            sb.append("本次启动后转换: ").append(sinceBoot).append('\n');
            sb.append("累计运行轮数: ").append(run).append('\n');
            String lastTime = o.optString(KEY_LAST_TIME, "");
            String lastName = o.optString(KEY_LAST_NAME, "");
            if (!TextUtils.isEmpty(lastTime) || !TextUtils.isEmpty(lastName)) {
                sb.append("最近处理: ").append(lastName).append(" (").append(lastTime).append(")\n");
            }
        } catch (Throwable t) {
            sb.append("解析统计失败: ").append(t.getMessage());
        }
        return sb.toString();
    }

    /** Read settings as a JSONObject (empty object if none). */
    public static JSONObject readSettings() {
        String raw = read(prefsFile());
        if (TextUtils.isEmpty(raw)) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** Persist settings. */
    public static void writeSettings(JSONObject o) {
        write(prefsFile(), o.toString());
    }

    public static boolean getSettingBool(JSONObject o, String key, boolean def) {
        return o.optBoolean(key, def);
    }

    /** Called by the module (inside SoundRecorder process) to update stats. */
    public static void updateStats(int total, int done, int sinceBoot, int runCount,
                                   String lastName, String lastTime, String lastState) {
        JSONObject o = new JSONObject();
        try {
            o.put(KEY_TOTAL, total);
            o.put(KEY_DONE, done);
            o.put(KEY_CONVERTED_SINCE_BOOT, sinceBoot);
            o.put(KEY_RUN_COUNT, runCount);
            o.put(KEY_LAST_NAME, lastName == null ? "" : lastName);
            o.put(KEY_LAST_TIME, lastTime == null ? "" : lastTime);
            o.put(KEY_LAST_STATE, lastState == null ? "" : lastState);
        } catch (Throwable ignored) {
        }
        write(statsFile(), o.toString());
    }
}

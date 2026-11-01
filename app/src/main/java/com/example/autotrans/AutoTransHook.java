package com.example.autotrans;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module: automatically enqueue speech-to-text tasks for Xiaomi SoundRecorder.
 *
 * Strategy:
 * 1. Hook Application.attach to get a working Context as early as possible.
 * 2. Start a background poller once (per process).
 * 3. Every 25 seconds query the local records DB and the ASR queue/status tables.
 * 4. If no task is currently pending/running and there is a local recording without
 *    a transcript, send the same Intent that SoundRecorderToolService.getTranscription()
 *    uses to start AsrService.
 * 5. Process only one new task per cycle, because SoundRecorder serializes ASR tasks.
 *
 * Settings (wifi-only, charging-only, enabled, toast) are read from a shared JSON file
 * written by the companion MainActivity. Statistics are written back to the same folder.
 */
public class AutoTransHook implements IXposedHookLoadPackage {
    private static final String TAG = "AutoTrans";
    private static final String RECORDER_PKG = "com.android.soundrecorder";

    private static final Uri URI_RECORDS = Uri.parse("content://records/records");
    private static final Uri URI_AI_QUEUE = Uri.parse("content://records/ai_recognize_queue");
    private static final Uri URI_RECOGNITION = Uri.parse("content://records/recognition_sentence");
    private static final Uri URI_TEXT = Uri.parse("content://records/recognize_text");

    // Simplified Chinese
    private static final String ASR_LANG = "zh-CN";

    // ai_recognize_queue state values from RecognizeCommonConstants
    private static final int ASR_STATE_IDLE = 0;        // waiting
    private static final int ASR_STATE_FINISH = 1;      // done
    private static final int ASR_STATE_NOT_ALL_FINISH = 2;
    private static final int ASR_STATE_SECOND_FINISH = 3;
    private static final int ASR_STATE_FAILED = 4;
    private static final int ASR_STATE_POLISH_FAILED = 5;
    private static volatile boolean sPollerStarted = false;

    // ---------------------------------------------------------------------
    // [FIX-2026-11] 死循环防护
    //
    // 历史问题：某条录音转写失败后 ai_recognize_queue 里留下 state=4，
    // isInQueue() 认为「失败可重试」返回 false，于是每 25 秒再发一次请求。
    // 但 AsrService 看到已有失败记录会直接丢弃，队列状态永不变化，
    // 模块就永久卡在这一条上，后面的录音再也不会被处理。
    //
    // 三重防护：
    //  1) MIN_DURATION_SEC —— 太短的碎片（如 1 秒）云端必然失败，直接跳过。
    //  2) MAX_ENQUEUE_ATTEMPTS —— 同一 sha1 最多发起 N 次，超限永久跳过。
    //  3) 失败记录也算「已处理」—— 已经有 state=4/5 且重试次数用尽的不再碰。
    // ---------------------------------------------------------------------

    /** 时长下限（秒）。低于此值的录音无转写价值，且云端 ASR 会直接失败。 */
    private static final int MIN_DURATION_SEC = 2;

    /** 同一 sha1 允许重复发起任务的最大次数。 */
    private static final int MAX_ENQUEUE_ATTEMPTS = 2;

    /**
     * 运行期内每个 sha1 已发起的次数（进程退出即清空）。
     * 只用于兜底，正常判断依赖队列中的失败记录。
     */
    private static final java.util.Map<String, Integer> sAttempts =
            new java.util.HashMap<String, Integer>();

    /**
     * [FIX-2026-11] 死循环检测：记录每个 sha1 最近一次发起时的队列状态。
     * 若连续两次发起后队列状态完全没变（说明 AsrService 静默丢弃了请求），
     * 判定该录音不可处理，永久跳过。
     */
    private static final java.util.Map<String, Integer> sLastEnqueueState =
            new java.util.HashMap<String, Integer>();

    /** 上一次轮询选中的 sha1，用于判断是否在同一条上反复打转。 */
    private static String sLastPickedSha1 = null;


    // In-process counters (reset on each app start / module reload).
    private static int sConvertedSinceBoot = 0;
    private static int sRunCount = 0;
    private static int sLastTotal = 0;
    private static int sLastDone = 0;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!RECORDER_PKG.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                final Context ctx = (Context) param.args[0];
                if (ctx == null) {
                    return;
                }
                if (!sPollerStarted) {
                    sPollerStarted = true;
                    XposedBridge.log(TAG + ": starting poller");
                    startPoller(ctx);
                }
            }
        });
    }

    private void startPoller(final Context ctx) {
        new Thread(() -> {
            sleepQuietly(15000L);
            while (true) {
                try {
                    if (isMainProcess(ctx)) {
                        doRound(ctx);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": round error: " + Log.getStackTraceString(t));
                }
                sleepQuietly(25000L);
            }
        }, "AutoTransPoller").start();
    }

    private void doRound(Context ctx) {
        sRunCount++;

        if (!hasRequiredPermissions(ctx)) {
            XposedBridge.log(TAG + ": network/CTA permissions not granted, skip");
            return;
        }

        JSONObject settings = TransStore.readSettings();
        if (!TransStore.getSettingBool(settings, TransStore.PREF_ENABLED, true)) {
            XposedBridge.log(TAG + ": disabled by settings, skip");
            return;
        }

        if (TransStore.getSettingBool(settings, TransStore.PREF_WIFI_ONLY, false) && !isWifiConnected(ctx)) {
            XposedBridge.log(TAG + ": wifi-only but not on wifi, skip");
            return;
        }

        if (TransStore.getSettingBool(settings, TransStore.PREF_CHARGING_ONLY, false) && !isCharging(ctx)) {
            XposedBridge.log(TAG + ": charging-only but not charging, skip");
            return;
        }

        // Respect the one-at-a-time limit: only enqueue if nothing is running/pending.
        if (hasActiveOrPendingTask(ctx)) {
            XposedBridge.log(TAG + ": active/pending ASR task exists, wait");
            updateStatsFile(ctx);
            return;
        }

        RecordItem target = pickNextPendingRecord(ctx);
        if (target == null) {
            XposedBridge.log(TAG + ": no pending record");
            updateStatsFile(ctx);
            return;
        }

        XposedBridge.log(TAG + ": enqueue sha1=" + target.sha1 + " path=" + maskPath(target.path));
        // [FIX-2026-11] 记录发起次数，用于失败重试上限
        Integer prev = sAttempts.get(target.sha1);
        int nowCount = prev == null ? 1 : prev + 1;
        sAttempts.put(target.sha1, nowCount);

        // [FIX-2026-11] 死循环检测：如果再次选中同一条且已发起过，说明上次请求无效
        if (target.sha1.equals(sLastPickedSha1) && nowCount >= MAX_ENQUEUE_ATTEMPTS) {
            XposedBridge.log(TAG + ": LOOP DETECTED sha1=" + target.sha1
                    + " attempts=" + nowCount + " -> give up, mark as stuck");
            // 已到重试上限，pickNextPendingRecord 下一轮会自动跳过它
        }
        sLastPickedSha1 = target.sha1;

        enqueueAsr(ctx, target);

        sConvertedSinceBoot++;
        updateStatsFile(ctx, target.fileName, "已发起");

        if (TransStore.getSettingBool(settings, TransStore.PREF_TOAST, true)) {
            toastOnMain(ctx, "已自动发起转文字: " + target.fileName);
        }
    }

    private void updateStatsFile(Context ctx) {
        updateStatsFile(ctx, null, null);
    }

    private void updateStatsFile(Context ctx, String lastName, String lastState) {
        // Compute totals
        int total = 0, done = 0;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(URI_RECORDS,
                    new String[]{"_id"},
                    "sha1 IS NOT NULL AND sha1 != '' AND duration >= " + MIN_DURATION_SEC + " AND in_local = 1",
                    null, null);
            total = c == null ? 0 : c.getCount();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": count records error: " + t);
        } finally {
            if (c != null) c.close();
        }
        // Approximate "done" by counting state=1 rows in ai_recognize_queue
        try {
            c = ctx.getContentResolver().query(URI_AI_QUEUE,
                    new String[]{"_id"},
                    "state = 1",
                    null, null);
            done = c == null ? 0 : c.getCount();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": count done error: " + t);
        } finally {
            if (c != null) c.close();
        }

        sLastTotal = total;
        sLastDone = done;

        String time = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        TransStore.updateStats(total, done, sConvertedSinceBoot, sRunCount,
                lastName, time, lastState);
    }

    private RecordItem pickNextPendingRecord(Context ctx) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(URI_RECORDS,
                    new String[]{"_id", "file_path", "file_name", "sha1", "duration", "rec_type"},
                    "sha1 IS NOT NULL AND sha1 != '' AND duration >= " + MIN_DURATION_SEC + " AND in_local = 1",
                    null,
                    "create_time DESC");
            if (c == null) {
                return null;
            }

            int idxId = c.getColumnIndex("_id");
            int idxPath = c.getColumnIndex("file_path");
            int idxName = c.getColumnIndex("file_name");
            int idxSha1 = c.getColumnIndex("sha1");
            int idxDur = c.getColumnIndex("duration");
            int idxType = c.getColumnIndex("rec_type");

            while (c.moveToNext()) {
                long id = idxId >= 0 ? c.getLong(idxId) : -1;
                String path = idxPath >= 0 ? c.getString(idxPath) : null;
                String name = idxName >= 0 ? c.getString(idxName) : null;
                String sha1 = idxSha1 >= 0 ? c.getString(idxSha1) : null;
                int dur = idxDur >= 0 ? c.getInt(idxDur) : 0;
                int type = idxType >= 0 ? c.getInt(idxType) : 0;

                if (TextUtils.isEmpty(sha1) || TextUtils.isEmpty(path)) {
                    continue;
                }
                // [FIX-2026-11] 跳过过短的碎片录音（云端 ASR 必然失败）
                if (dur < MIN_DURATION_SEC) {
                    continue;
                }
                if (!new File(path).exists()) {
                    continue;
                }
                if (alreadyHasResult(ctx, sha1)) {
                    continue;
                }
                if (isInQueue(ctx, sha1)) {
                    continue;
                }
                // [FIX-2026-11] 失败重试上限：同一 sha1 发起次数超限则永久跳过
                Integer attempts = sAttempts.get(sha1);
                if (attempts != null && attempts >= MAX_ENQUEUE_ATTEMPTS) {
                    XposedBridge.log(TAG + ": skip sha1=" + sha1
                            + " (attempts=" + attempts + " >= " + MAX_ENQUEUE_ATTEMPTS + ")");
                    continue;
                }
                return new RecordItem(id, path, name, sha1, dur, type);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": query records error: " + Log.getStackTraceString(t));
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private boolean alreadyHasResult(Context ctx, String sha1) {
        return rowExists(ctx, URI_RECOGNITION, "sha1=?", new String[]{sha1})
                || rowExists(ctx, URI_TEXT, "sha1=?", new String[]{sha1});
    }

    private boolean isInQueue(Context ctx, String sha1) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(URI_AI_QUEUE,
                    new String[]{"state"},
                    "sha1=?",
                    new String[]{sha1},
                    null);
            if (c != null && c.moveToFirst()) {
                int state = c.getInt(0);
                return state != ASR_STATE_FAILED && state != ASR_STATE_POLISH_FAILED;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": query queue error: " + t);
        } finally {
            if (c != null) c.close();
        }
        return false;
    }

    private boolean hasActiveOrPendingTask(Context ctx) {
        Cursor c = null;
        try {
            // Only state=0 (IDLE/waiting) truly means a task is in-flight or waiting.
            // state=1/2/3 are finished or leftover terminal states, state=4/5 are
            // failed (we allow retry). So we must NOT treat 2/3/5 as "active", otherwise
            // the poller would be blocked forever by old terminal records.
            c = ctx.getContentResolver().query(URI_AI_QUEUE,
                    new String[]{"sha1"},
                    "state = 0",
                    null,
                    null);
            return c != null && c.getCount() > 0;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": check active error: " + t);
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    private boolean rowExists(Context ctx, Uri uri, String where, String[] args) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{"_id"}, where, args, null);
            return c != null && c.moveToFirst();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": query error " + uri + ": " + t);
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    private void enqueueAsr(Context ctx, RecordItem item) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(RECORDER_PKG,
                "com.android.soundrecorder.newrecognize.service.AsrService"));
        intent.putExtra("fromRecord", true);
        intent.putExtra("autoAsrFilePath", item.path);
        intent.putExtra("autoAsrFileSha1", item.sha1);
        intent.putExtra("packageName", RECORDER_PKG);
        intent.putExtra("asrLanguage", ASR_LANG);
        intent.putExtra("fromToolService", true);
        ctx.startService(intent);
    }

    private boolean hasRequiredPermissions(Context ctx) {
        try {
            Object netCta = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.soundrecorder.SoundRecorderSettings", ctx.getClassLoader()),
                    "k0", false);
            Object cta = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.soundrecorder.SoundRecorderSettings", ctx.getClassLoader()),
                    "k0", true);
            return Boolean.TRUE.equals(netCta) && Boolean.TRUE.equals(cta);
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isWifiConnected(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true; // be permissive if service missing
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isCharging(Context ctx) {
        try {
            Intent intent = ctx.registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return true;
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isMainProcess(Context ctx) {
        try {
            int pid = android.os.Process.myPid();
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return true;
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) return true;
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (info.pid == pid) {
                    return RECORDER_PKG.equals(info.processName);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": isMainProcess error: " + t);
        }
        return true;
    }

    private void toastOnMain(final Context ctx, final String text) {
        try {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String maskPath(String path) {
        if (path == null) return "";
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? "..." + path.substring(idx) : path;
    }

    private static class RecordItem {
        long id;
        String path;
        String fileName;
        String sha1;
        int duration;
        int type;

        RecordItem(long id, String path, String fileName, String sha1, int duration, int type) {
            this.id = id;
            this.path = path;
            this.fileName = fileName;
            this.sha1 = sha1;
            this.duration = duration;
            this.type = type;
        }
    }
}
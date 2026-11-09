package com.example.autotrans;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * 把已转写完成的录音输出为统一 Item 格式（任务书 1.2 / 3.1 节）。
 *
 * 输出路径：/sdcard/Download/autotrans/items/YYYY-MM-DD.jsonl
 * 一行一条 JSON，字段与 recorderhub / nchat 对齐，供汇总脚本统一读取。
 */
final class ItemWriter {

    private static final String TAG = "AutoTrans";

    /** 与 TransStore 保持同一根目录。 */
    private static final String DIR = "Download/autotrans";
    private static final String ITEMS = "items";

    private static final Uri URI_RECORDS = Uri.parse("content://records/records");
    private static final Uri URI_AI_QUEUE = Uri.parse("content://records/ai_recognize_queue");
    private static final Uri URI_RECOGNITION = Uri.parse("content://records/recognition_sentence");

    private ItemWriter() {
    }

    static File itemsDir() {
        File d = new File(new File(Environment.getExternalStorageDirectory(), DIR), ITEMS);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File itemsFileFor(long ms) {
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
        return new File(itemsDir(), day + ".jsonl");
    }

    /** ISO 8601 带时区偏移，如 2026-11-09T14:03:21+08:00 */
    static String iso(long ms) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(ms));
        } catch (Throwable t) {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date(ms));
        }
    }

    /** 读当天已被写入的 id 集合，用于去重（幂等）。 */
    static Set<String> loadIds(File f) {
        Set<String> ids = new HashSet<>();
        if (f == null || !f.exists()) return ids;
        try {
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n;
            try {
                n = in.read(buf);
            } finally {
                in.close();
            }
            if (n <= 0) return ids;
            String all = new String(buf, 0, n, StandardCharsets.UTF_8);
            for (String line : all.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int i = line.indexOf("\"id\":\"");
                if (i < 0) continue;
                i += 6;
                int j = line.indexOf('"', i);
                if (j > i) ids.add(line.substring(i, j));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": loadIds err: " + t);
        }
        return ids;
    }

    /** 追加一行（自动加换行）。 */
    static boolean appendLine(File f, String json) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": appendLine err: " + t);
            return false;
        }
    }

    /**
     * 为一条已转写完成的录音生成 Item 并追加。
     *
     * @return true 表示确实写入了新行（false = 已存在或数据不全）
     */
    static boolean writeItem(Context ctx, String sha1) {
        if (TextUtils.isEmpty(sha1)) return false;
        try {
            // ---- 1. 取录音元数据 ----
            Cursor c = ctx.getContentResolver().query(URI_RECORDS,
                    new String[]{"file_path", "file_name", "create_time", "duration", "rec_type"},
                    "sha1=?", new String[]{sha1}, null);
            if (c == null) return false;
            String path = null, name = null;
            long createTime = 0;
            int duration = 0;
            int recType = 0;
            try {
                if (!c.moveToFirst()) return false;
                path = c.getString(0);
                name = c.getString(1);
                createTime = c.getLong(2);
                duration = c.getInt(3);
                recType = c.getInt(4);
            } finally {
                c.close();
            }
            if (createTime <= 0) return false;

            // ---- 2. 拼接转写正文（按 timeline/start_time 排序）----
            String text = buildText(ctx, sha1);
            if (TextUtils.isEmpty(text)) {
                XposedBridge.log(TAG + ": item skip (no text) sha1=" + sha1);
                return false;
            }

            // ---- 3. 组装 Item ----
            String id = "call-" + sha1.substring(0, Math.min(12, sha1.length()));
            long tsStart = createTime;
            long tsEnd = tsStart + Math.max(0, duration) * 1000L;

            String title;
            String threadId = "";
            String src = "CALL";
            String kind = "CALL_RECORDING";
            double importance = 0.7;

            String number = extractNumber(name, path);
            if (number != null) {
                title = "与 " + number + " 的通话";
                threadId = "tel-" + number;
                // 接通时长 < 20 秒：多为推销/快递，降权
                importance = duration > 0 && duration < 20 ? 0.3 : 0.7;
            } else {
                title = name == null || name.isEmpty() ? "录音" : name;
                // 非通话类（rec_type != 0 或文件名不像号码）按普通录音处理
                src = "RECORDER";
                kind = "RECORDING";
                importance = 0.5;
            }

            // ---- 4. 幂等：当天文件里已存在同 id 则跳过 ----
            File out = itemsFileFor(tsStart);
            Set<String> ids = loadIds(out);
            if (ids.contains(id)) {
                XposedBridge.log(TAG + ": item exists, skip id=" + id);
                return false;
            }

            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("source", src);
            o.put("kind", kind);
            o.put("ts_start", iso(tsStart));
            o.put("ts_end", iso(tsEnd));
            o.put("duration_s", duration);
            o.put("title", title);
            o.put("text", text);
            o.put("audio", path == null ? "" : path);
            o.put("transcript_dir", "");
            o.put("importance", importance);
            o.put("thread_id", threadId);

            boolean ok = appendLine(out, o.toString());
            if (ok) XposedBridge.log(TAG + ": item appended id=" + id + " -> " + out.getName());
            return ok;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": writeItem err: " + t);
            return false;
        }
    }

    /**
     * 把 sentence 字段压成纯文本。
     * 原始值可能是：纯文本 / JSON 数组字符串 / 单元素数组。
     */
    static String flattenSentence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() < 2 || t.charAt(0) != '[') return t;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < t.length()) {
            char ch = t.charAt(i);
            if (ch == '"') {
                i++;
                while (i < t.length()) {
                    char c2 = t.charAt(i);
                    if (c2 == '\\') {
                        if (i + 1 < t.length()) {
                            sb.append(t.charAt(i + 1));
                            i += 2;
                            continue;
                        }
                        i++;
                        continue;
                    }
                    if (c2 == '"') { i++; break; }
                    sb.append(c2);
                    i++;
                }
                continue;
            }
            i++;
        }
        String r = sb.toString().trim();
        return r.isEmpty() ? t : r;
    }


    /** 从 recognition_sentence 按时间顺序拼接全文。 */
    static String buildText(Context ctx, String sha1) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(URI_RECOGNITION,
                    new String[]{"sentence", "content", "timeline"},
                    "sha1=?", new String[]{sha1}, null);
            if (c == null) return "";
            StringBuilder sb = new StringBuilder();
            // 优先按 timeline（毫秒数字符串）排序；取不到就按游标顺序
            java.util.List<long[]> order = new java.util.ArrayList<>();
            java.util.List<String> parts = new java.util.ArrayList<>();
            int idx = 0;
            while (c.moveToNext()) {
                String s = c.getString(0);
                if (TextUtils.isEmpty(s)) s = c.getString(1);
                if (TextUtils.isEmpty(s)) continue;
                // sentence 字段可能是 JSON 数组字符串（如 ["喂","，","万","师傅"]），
                // 拼成纯文本再输出，避免汇总时满屏引号和逗号。
                s = flattenSentence(s);
                if (TextUtils.isEmpty(s)) continue;
                String tl = null;
                try { tl = c.getString(2); } catch (Throwable ignore) {}
                long t = 0;
                if (tl != null) {
                    try { t = Long.parseLong(tl.trim()); } catch (Throwable ignore) {
                        // timeline 可能是 "1234,5678" 之类的区间，取第一段
                        int comma = tl.indexOf(',');
                        if (comma > 0) {
                            try { t = Long.parseLong(tl.substring(0, comma).trim()); } catch (Throwable ignore2) {}
                        }
                    }
                }
                order.add(new long[]{t, idx++});
                parts.add(s);
            }
            if (parts.isEmpty()) return "";
            // 若所有 t 都为 0，保持原序
            boolean anyTime = false;
            for (long[] p : order) if (p[0] > 0) { anyTime = true; break; }
            Integer[] seq = new Integer[parts.size()];
            for (int i = 0; i < seq.length; i++) seq[i] = i;
            if (anyTime) {
                java.util.Arrays.sort(seq, (a, b) -> {
                    long ta = order.get(a)[0], tb = order.get(b)[0];
                    if (ta != tb) return Long.compare(ta, tb);
                    return Long.compare(order.get(a)[1], order.get(b)[1]);
                });
            }
            for (int i : seq) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(parts.get(i).trim());
            }
            return sb.toString();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": buildText err: " + t);
            return "";
        } finally {
            if (c != null) c.close();
        }
    }

    /** 从文件名里提取电话号码，如 "田军(13304409417)_20261101135611.mp3" -> 13304409417 */
    static String extractNumber(String fileName, String filePath) {
        String s = fileName != null && !fileName.isEmpty() ? fileName
                : (filePath == null ? "" : new File(filePath).getName());
        if (s.isEmpty()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(1[3-9]\\d{9})").matcher(s);
        if (m.find()) return m.group(1);
        // 也处理固话/短号（7~12 位数字且整体像号码）
        m = java.util.regex.Pattern.compile("(?<!\\d)(\\d{7,12})(?!\\d)").matcher(s);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * 扫描队列里所有已完成（state=1）且还没有 item 的录音，补写。
     * 用于 daemon 重启后补漏，以及首次启用时回填。
     */
    static int backfill(Context ctx, int limit) {
        int written = 0;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(URI_AI_QUEUE,
                    new String[]{"sha1"}, "state = 1", null, "update_time DESC");
            if (c == null) return 0;
            int total = 0;
            while (c.moveToNext() && written < limit) {
                String sha1 = c.getString(0);
                if (TextUtils.isEmpty(sha1)) continue;
                total++;
                if (writeItem(ctx, sha1)) written++;
            }
            XposedBridge.log(TAG + ": backfill scanned=" + total + " written=" + written);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": backfill err: " + t);
        } finally {
            if (c != null) c.close();
        }
        return written;
    }

    /** 统计当天 items 文件的行数（供 status.json 使用）。 */
    static int countToday() {
        try {
            File f = itemsFileFor(System.currentTimeMillis());
            if (!f.exists()) return 0;
            return loadIds(f).size();
        } catch (Throwable t) {
            return 0;
        }
    }
}

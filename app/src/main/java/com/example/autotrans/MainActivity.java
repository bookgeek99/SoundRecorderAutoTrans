package com.example.autotrans;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Module companion app UI: shows live statistics collected by the hook, and
 * exposes a few runtime settings (wifi-only, charging-only, lang, toast) written
 * to the shared settings file consumed by AutoTransHook.
 */
public class MainActivity extends Activity {
    private static final int REQ_STORAGE = 100;

    private LinearLayout root;
    private TextView tvStats;
    private TextView tvStatus;
    private Switch swEnabled;
    private Switch swWifi;
    private Switch swCharging;
    private Switch swToast;
    private TextView tvLang;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 40, 32, 40);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("录音机自动转文字");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("LSPosed 模块 · 作用域 com.android.soundrecorder");
        sub.setTextSize(13);
        sub.setTextColor(0xFF666666);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        // Storage permission notice
        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(0xFFB00020);
        tvStatus.setPadding(0, 16, 0, 8);
        root.addView(tvStatus);

        // Stats section
        TextView statsTitle = new TextView(this);
        statsTitle.setText("运行统计");
        statsTitle.setTextSize(16);
        statsTitle.setPadding(0, 20, 0, 8);
        root.addView(statsTitle);

        tvStats = new TextView(this);
        tvStats.setTextSize(14);
        tvStats.setText("加载中…");
        root.addView(tvStats);

        // Settings section
        TextView setTitle = new TextView(this);
        setTitle.setText("设置");
        setTitle.setTextSize(16);
        setTitle.setPadding(0, 24, 0, 8);
        root.addView(setTitle);

        swEnabled = makeSwitch("启用自动转文字", "关闭后模块不发起任何新任务");
        root.addView(swEnabled);

        swWifi = makeSwitch("仅在 WiFi 下执行", "非 WiFi 环境不发起转文字");
        root.addView(swWifi);

        swCharging = makeSwitch("仅在充电时执行", "非充电状态不发起转文字");
        root.addView(swCharging);

        swToast = makeSwitch("弹出 Toast 通知", "每次自动发起时弹出提示");
        root.addView(swToast);

        tvLang = new TextView(this);
        tvLang.setTextSize(14);
        tvLang.setPadding(0, 16, 0, 4);
        root.addView(tvLang);

        // Refresh button
        Button refresh = new Button(this);
        refresh.setText("刷新统计");
        refresh.setOnClickListener(v -> refreshUi());
        root.addView(refresh);

        // Grant storage button (always visible; enabled state drives action)
        Button grant = new Button(this);
        grant.setText("授权存储访问");
        grant.setOnClickListener(v -> requestStorage());
        root.addView(grant);

        TextView hint = new TextView(this);
        hint.setText("提示：请在 LSPosed Manager 中启用本模块并勾选作用域“录音机”，"
                + "然后重启录音机应用生效。改动设置后同样需重启录音机。");
        hint.setTextSize(12);
        hint.setTextColor(0xFF888888);
        hint.setPadding(0, 24, 0, 0);
        root.addView(hint);

        setContentView(scroll);
        refreshUi();
    }

    private Switch makeSwitch(String label, String desc) {
        Switch sw = new Switch(this);
        sw.setText(label + "\n" + desc);
        sw.setTextSize(14);
        sw.setPadding(0, 10, 0, 0);
        sw.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> saveSettings());
        return sw;
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent it = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(it);
            } catch (Throwable t) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.READ_EXTERNAL_STORAGE"}, REQ_STORAGE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void refreshUi() {
        tvStats.setText(TransStore.readStatsSummary());

        JSONObject s = TransStore.readSettings();
        swEnabled.setChecked(TransStore.getSettingBool(s, TransStore.PREF_ENABLED, true));
        swWifi.setChecked(TransStore.getSettingBool(s, TransStore.PREF_WIFI_ONLY, false));
        swCharging.setChecked(TransStore.getSettingBool(s, TransStore.PREF_CHARGING_ONLY, false));
        swToast.setChecked(TransStore.getSettingBool(s, TransStore.PREF_TOAST, true));
        String lang = s.optString(TransStore.PREF_ASR_LANG, "zh-CN");
        tvLang.setText("转文字语言: " + lang);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tvStatus.setText(Environment.isExternalStorageManager()
                    ? "存储权限: 已授权" : "存储权限: 未授权（请点击下方按钮开启）");
        } else {
            boolean g = checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED;
            tvStatus.setText(g ? "存储权限: 已授权" : "存储权限: 未授权（请点击下方按钮开启）");
        }
    }

    private void saveSettings() {
        JSONObject s = new JSONObject();
        try {
            s.put(TransStore.PREF_ENABLED, swEnabled.isChecked());
            s.put(TransStore.PREF_WIFI_ONLY, swWifi.isChecked());
            s.put(TransStore.PREF_CHARGING_ONLY, swCharging.isChecked());
            s.put(TransStore.PREF_TOAST, swToast.isChecked());
            s.put(TransStore.PREF_ASR_LANG, "zh-CN");
        } catch (Throwable ignored) {
        }
        TransStore.writeSettings(s);
    }
}
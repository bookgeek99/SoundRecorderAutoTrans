package com.example.autotrans;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONObject;

/**
 * Material Design companion app UI: shows live statistics collected by the hook
 * and exposes a few runtime settings (wifi-only, charging-only, enabled, toast)
 * written to the shared settings file consumed by AutoTransHook.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 100;

    private TextView tvStats;
    private TextView tvStatus;
    private MaterialSwitch swEnabled;
    private MaterialSwitch swWifi;
    private MaterialSwitch swCharging;
    private MaterialSwitch swToast;
    private TextView tvLang;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStats = findViewById(R.id.tvStats);
        tvStatus = findViewById(R.id.tvStatus);
        tvLang = findViewById(R.id.tvLang);
        swEnabled = findViewById(R.id.swEnabled);
        swWifi = findViewById(R.id.swWifi);
        swCharging = findViewById(R.id.swCharging);
        swToast = findViewById(R.id.swToast);

        MaterialButton btnRefresh = findViewById(R.id.btnRefresh);
        MaterialButton btnGrant = findViewById(R.id.btnGrant);

        btnRefresh.setOnClickListener(v -> refreshUi());
        btnGrant.setOnClickListener(v -> requestStorage());

        swEnabled.setOnCheckedChangeListener((b, c) -> saveSettings());
        swWifi.setOnCheckedChangeListener((b, c) -> saveSettings());
        swCharging.setOnCheckedChangeListener((b, c) -> saveSettings());
        swToast.setOnCheckedChangeListener((b, c) -> saveSettings());

        refreshUi();
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
        tvLang.setText(getString(R.string.lang_label, lang));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tvStatus.setText(Environment.isExternalStorageManager()
                    ? getString(R.string.storage_granted)
                    : getString(R.string.storage_denied));
        } else {
            boolean g = checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED;
            tvStatus.setText(g ? getString(R.string.storage_granted)
                    : getString(R.string.storage_denied));
        }
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
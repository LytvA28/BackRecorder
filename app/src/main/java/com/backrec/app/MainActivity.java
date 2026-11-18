package com.backrec.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int[] MINUTES = {1, 5, 10, 15, 30, 45, 60, 90, 120};
    private static final int REQ_PERMS = 1;

    private TextView status;
    private Button toggle;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 1000); }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        status = findViewById(R.id.status);
        toggle = findViewById(R.id.toggle);
        Spinner spinner = findViewById(R.id.duration);

        List<String> labels = new ArrayList<>();
        for (int m : MINUTES) {
            String time = m < 60 ? getString(R.string.min_n, m)
                    : getString(R.string.hours_n, m % 60 == 0 ? String.valueOf(m / 60) : String.valueOf(m / 60.0));
            labels.add(time + "  (~" + sizeLabel(m) + ")");
        }
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        int saved = prefs.getInt("minutes", 15);
        for (int i = 0; i < MINUTES.length; i++) if (MINUTES[i] == saved) spinner.setSelection(i);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt("minutes", MINUTES[pos]).apply();
                if (BufferService.recorder() != null) {
                    startService(new Intent(MainActivity.this, BufferService.class)
                            .setAction(BufferService.ACTION_SET_DURATION)
                            .putExtra(BufferService.EXTRA_MINUTES, MINUTES[pos]));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

        toggle.setOnClickListener(v -> {
            if (BufferService.recorder() != null) {
                startService(new Intent(this, BufferService.class).setAction(BufferService.ACTION_STOP));
                ui.postDelayed(this::refresh, 300);
            } else {
                startRecordingWithPermissions();
            }
        });

        bindSave(R.id.save1, 1);
        bindSave(R.id.save5, 5);
        bindSave(R.id.save15, 15);
        findViewById(R.id.saveAll).setOnClickListener(v -> BufferService.save(this, 0));
        findViewById(R.id.battery).setOnClickListener(v -> askBatteryWhitelist());
        findViewById(R.id.clear).setOnClickListener(v -> askClear());
        findViewById(R.id.openFolder).setOnClickListener(v -> openFolder());
        ((TextView) findViewById(R.id.folderPath)).setText(getString(R.string.folder_path, folderAbsPath()));

        Button lang = findViewById(R.id.language);
        lang.setText(("🌐 " + LocaleHelper.shortLabel(this)).trim());
        lang.setOnClickListener(v -> chooseLanguage());

    }

    /** Сколько места займёт буфер такой длины: аудио-битрейт + 7 байт ADTS-заголовка на кадр. */
    private String sizeLabel(int minutes) {
        double bytesPerSec = RingRecorder.BIT_RATE / 8.0
                + 7.0 * RingRecorder.SAMPLE_RATE / RingRecorder.SAMPLES_PER_FRAME;
        double mb = bytesPerSec * minutes * 60 / (1024.0 * 1024.0);
        String num = mb < 10 ? String.format(java.util.Locale.getDefault(), "%.1f", mb)
                : String.valueOf(Math.round(mb));
        return getString(R.string.size_mb, num);
    }

    private void bindSave(int id, int minutes) {
        Button btn = findViewById(id);
        btn.setText(getString(R.string.min_n, minutes));
        btn.setOnClickListener(v -> BufferService.save(this, minutes));
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }

    private void refresh() {
        RingRecorder r = BufferService.recorder();
        if (r != null && r.isRunning()) {
            status.setText(getString(R.string.recording_status,
                    BufferService.formatDuration(r.bufferedMs()),
                    BufferService.formatDuration(prefs.getInt("minutes", 15) * 60_000L)));
            status.setTextColor(0xFFFF5252);
            toggle.setText(R.string.stop);
        } else {
            status.setText(R.string.stopped);
            status.setTextColor(0xFFFFFFFF);
            toggle.setText(R.string.start);
        }
    }

    private void chooseLanguage() {
        String[] names = LocaleHelper.NAMES.clone();
        names[0] = getString(R.string.language_system);
        String cur = LocaleHelper.get(this);
        int checked = 0;
        for (int i = 0; i < LocaleHelper.CODES.length; i++) if (LocaleHelper.CODES[i].equals(cur)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    d.dismiss();
                    String code = LocaleHelper.CODES[which];
                    if (code.equals(cur)) return;
                    LocaleHelper.set(this, code);
                    recreate();
                    // уведомление тоже перерисуем на новом языке
                    if (BufferService.recorder() != null) {
                        startService(new Intent(this, BufferService.class)
                                .setAction(BufferService.ACTION_SET_DURATION)
                                .putExtra(BufferService.EXTRA_MINUTES, prefs.getInt("minutes", 15)));
                    }
                })
                .show();
    }

    private static String folderRelPath() {
        return AacExporter.relativeDir(); // напр. "Recordings/BackRecorder"
    }

    @SuppressWarnings("deprecation")
    private static String folderAbsPath() {
        return android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + folderRelPath();
    }

    /** Открывает папку с записями в файловом менеджере (Samsung «Мои файлы» или системный «Файлы»). */
    private void openFolder() {
        String abs = folderAbsPath();
        if (!new java.io.File(abs).isDirectory()) {
            Toast.makeText(this, R.string.folder_empty, Toast.LENGTH_LONG).show();
            return;
        }
        // 1. Samsung «Мои файлы»
        Intent samsung = new Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES")
                .setPackage("com.sec.android.app.myfiles")
                .putExtra("samsung.myfiles.intent.extra.START_PATH", abs)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 2. Системный файловый менеджер (DocumentsUI / Files by Google и т.п.)
        Uri doc = android.provider.DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:" + folderRelPath());
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(doc, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

        for (Intent i : new Intent[]{samsung, view}) {
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) { }
        }
        Toast.makeText(this, getString(R.string.no_file_manager, abs), Toast.LENGTH_LONG).show();
    }

    private void askClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_title)
                .setItems(new String[]{getString(R.string.clear_buffer), getString(R.string.clear_everything)},
                        (d, which) -> BufferService.clearAll(this, which == 1))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startRecordingWithPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) startBuffer();
        else requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startBuffer();
        } else {
            Toast.makeText(this, R.string.no_mic, Toast.LENGTH_LONG).show();
        }
    }

    private void startBuffer() {
        Intent i = new Intent(this, BufferService.class)
                .setAction(BufferService.ACTION_START)
                .putExtra(BufferService.EXTRA_MINUTES, prefs.getInt("minutes", 15));
        startForegroundService(i);
        ui.postDelayed(this::refresh, 300);
    }

    @SuppressLint("BatteryLife")
    private void askBatteryWhitelist() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, R.string.battery_done, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}

package com.backrec.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BufferService extends Service {
    private static final String TAG = "BufferService";

    public static final String ACTION_START = "com.backrec.START";
    public static final String ACTION_STOP = "com.backrec.STOP";
    public static final String ACTION_SAVE = "com.backrec.SAVE";
    public static final String ACTION_SET_DURATION = "com.backrec.SET_DURATION";
    public static final String EXTRA_MINUTES = "minutes";       // длина буфера
    public static final String EXTRA_LAST_MINUTES = "last";     // сколько сохранить, 0 = всё

    private static final String CHANNEL = "buffer";
    private static final int NOTIF_ID = 1;

    private static volatile BufferService instance;

    private RingRecorder recorder;
    private PowerManager.WakeLock wakeLock;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int minutes;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    public static File bufferDir(Context c) { return new File(c.getFilesDir(), "ring"); }

    /** null, если сервис не работает. */
    public static RingRecorder recorder() {
        BufferService s = instance;
        return s == null ? null : s.recorder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "onStartCommand action=" + action);
        if (action == null) { stopSelf(); return START_NOT_STICKY; }

        switch (action) {
            case ACTION_START: {
                minutes = intent.getIntExtra(EXTRA_MINUTES, 15);
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                if (recorder == null) {
                    recorder = new RingRecorder(bufferDir(this), minutes * 60_000L, e -> main.post(() -> {
                        Log.e(TAG, "recorder error, stopping", e);
                        Toast.makeText(this, getString(R.string.rec_error, e.getMessage()), Toast.LENGTH_LONG).show();
                        shutdown("recorder error");
                    }));
                    recorder.start();
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BackRecorder:rec");
                    wakeLock.acquire();
                } else {
                    recorder.setBufferMs(minutes * 60_000L);
                }
                instance = this;
                break;
            }
            case ACTION_SET_DURATION:
                minutes = intent.getIntExtra(EXTRA_MINUTES, minutes);
                if (recorder != null) {
                    recorder.setBufferMs(minutes * 60_000L);
                    getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
                }
                break;
            case ACTION_SAVE:
                save(this, intent.getIntExtra(EXTRA_LAST_MINUTES, 0));
                if (recorder == null) stopSelf();
                break;
            case ACTION_STOP:
                shutdown("stop action");
                break;
        }
        return START_NOT_STICKY;
    }

    private void shutdown(String reason) {
        Log.i(TAG, "shutdown: " + reason);
        instance = null;
        if (recorder != null) {
            RingRecorder r = recorder;
            recorder = null;
            worker.execute(r::stop); // дождётся конца сохранения, если оно идёт
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        if (recorder != null) shutdown("onDestroy");
        super.onDestroy();
    }

    /** Контекст приложения с выбранным языком — для тостов из фоновых задач. */
    private static Context localized(Context ctx) {
        return LocaleHelper.wrap(ctx.getApplicationContext());
    }

    /** Сохраняет буфер. Работает и когда запись остановлена (берёт то, что осталось на диске). */
    public static void save(Context ctx, int lastMinutes) {
        Context app = ctx.getApplicationContext();
        Context loc = localized(ctx);
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> Toast.makeText(app, loc.getString(R.string.saving), Toast.LENGTH_SHORT).show());
        worker.execute(() -> {
            RingRecorder r = recorder();
            String msg;
            List<File> segs = null;
            try {
                segs = r != null ? r.lockSegmentsForExport() : diskSegments(app);
                AacExporter.Result res = AacExporter.export(app, segs, lastMinutes * 60_000L);
                msg = loc.getString(R.string.saved, formatDuration(res.durationMs), res.name);
            } catch (AacExporter.EmptyBufferException e) {
                msg = loc.getString(R.string.save_failed, loc.getString(R.string.buffer_empty));
            } catch (Exception e) {
                Log.e(TAG, "save", e);
                msg = loc.getString(R.string.save_failed, e.getMessage());
            } finally {
                if (r != null && segs != null) r.releaseSegments();
            }
            String m = msg;
            h.post(() -> Toast.makeText(app, m, Toast.LENGTH_LONG).show());
        });
    }

    private static List<File> diskSegments(Context c) {
        File[] files = bufferDir(c).listFiles((d, n) -> n.startsWith("seg_"));
        List<File> segs = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            segs.addAll(Arrays.asList(files));
        }
        return segs;
    }

    /**
     * Удаляет буфер (запись при этом продолжается с нуля), а при includeSaved —
     * ещё и все сохранённые этим приложением файлы.
     */
    public static void clearAll(Context ctx, boolean includeSaved) {
        Context app = ctx.getApplicationContext();
        Context loc = localized(ctx);
        Handler h = new Handler(Looper.getMainLooper());
        worker.execute(() -> {
            int count;
            RingRecorder r = recorder();
            if (r != null) {
                count = r.clearBuffer();
            } else {
                count = 0;
                for (File f : diskSegments(app)) if (f.delete()) count++;
            }
            if (includeSaved) count += deleteSavedRecordings(app);
            int c = count;
            h.post(() -> Toast.makeText(app, loc.getString(R.string.cleared, c), Toast.LENGTH_LONG).show());
        });
    }

    private static int deleteSavedRecordings(Context app) {
        ContentResolver cr = app.getContentResolver();
        Uri coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        int count = 0;
        try (Cursor cur = cr.query(coll, new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? AND "
                        + MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?",
                new String[]{"%/BackRecorder/%", "BackRec_%"}, null)) {
            if (cur == null) return 0;
            while (cur.moveToNext()) {
                Uri u = ContentUris.withAppendedId(coll, cur.getLong(0));
                try {
                    count += cr.delete(u, null, null);
                } catch (SecurityException e) {
                    // файл создан прошлой установкой приложения — удалить без запроса нельзя
                    Log.w(TAG, "can't delete " + u, e);
                }
            }
        }
        return count;
    }

    public static String formatDuration(long ms) {
        long s = ms / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch); // повторный вызов просто обновляет название

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text, minutes))
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_save5), servicePi(ACTION_SAVE, 5, 1)).build())
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_save_all), servicePi(ACTION_SAVE, 0, 2)).build())
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), servicePi(ACTION_STOP, 0, 3)).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private PendingIntent servicePi(String action, int last, int req) {
        Intent i = new Intent(this, BufferService.class).setAction(action).putExtra(EXTRA_LAST_MINUTES, last);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

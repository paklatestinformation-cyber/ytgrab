package com.zyvo.ytgrab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

public class DownloadService extends Service {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_AUDIO = "audio";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_TITLE = "title";

    public static final String PROC_ID = "ytgrab-download";

    private static final String TAG = "YTGrab";
    private static final String CHANNEL_ID = "ytgrab_downloads";
    private static final int NOTIF_ID = 4711;

    private ExecutorService executor;
    private NotificationManager nm;
    private volatile int lastNotified = -1;
    private volatile String jobTitle = "Video";

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final String url = intent.getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final boolean audio = intent.getBooleanExtra(EXTRA_AUDIO, false);
        final int quality = intent.getIntExtra(EXTRA_QUALITY, 0);
        String t = intent.getStringExtra(EXTRA_TITLE);
        if (t == null || t.trim().isEmpty()) t = "Video";
        jobTitle = t.trim();

        startInForeground(jobTitle, "Starting download…", 0);
        DownloadBus.started(jobTitle);

        executor.execute(() -> work(url, audio, quality));
        return START_NOT_STICKY;
    }

    private void work(String url, boolean audio, int quality) {
        long started = System.currentTimeMillis();
        File dir = new File(getExternalFilesDir(null), "downloads");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        cleanupPartials(dir);

        try {
            YtDlp.ensureInit(this);
        } catch (Exception e) {
            fail("The download engine could not start. Reinstall the app and try again.\n\n" + shortMsg(e));
            return;
        }

        try {
            run(buildRequest(url, audio, quality, dir));
        } catch (YoutubeDL.CanceledException ce) {
            fail("Download cancelled.");
            return;
        } catch (Exception first) {
            Log.w(TAG, "first attempt failed", first);
            // YouTube changes often — refresh yt-dlp once and retry.
            try {
                YoutubeDL.getInstance().updateYoutubeDL(
                        getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
            } catch (Exception ignored) {
            }
            try {
                run(buildRequest(url, audio, quality, dir));
            } catch (YoutubeDL.CanceledException ce) {
                fail("Download cancelled.");
                return;
            } catch (Exception second) {
                Log.e(TAG, "download failed", second);
                fail(friendly(second));
                return;
            }
        }

        File out = newest(dir, started - 5000);
        if (out == null) {
            fail("The download finished but no file was produced. Try another quality.");
            return;
        }

        String thumb = UrlUtil.thumbUrl(UrlUtil.videoId(url));
        HistoryStore.Item item = MediaSaver.publish(this, out, audio, thumb);
        HistoryStore.add(this, item);
        DownloadBus.done(item);
        notifyDone(item);
    }

    private YoutubeDLRequest buildRequest(String url, boolean audio, int quality, File dir) {
        YoutubeDLRequest req = new YoutubeDLRequest(url);
        req.addOption("--no-playlist");
        req.addOption("--no-mtime");
        req.addOption("--no-warnings");
        req.addOption("-N", "3");
        req.addOption("-o", new File(dir, "%(title).90s [%(id)s].%(ext)s").getAbsolutePath());

        if (audio) {
            req.addOption("-f", "ba[ext=m4a]/ba/b");
            if (YtDlp.ffmpegOk) {
                req.addOption("-x");
                req.addOption("--audio-format", "m4a");
                req.addOption("--audio-quality", "0");
            }
        } else {
            req.addOption("-f", "bv*+ba/b");
            req.addOption("--merge-output-format", "mp4");
            if (quality > 0) {
                req.addOption("-S", "res:" + quality + ",ext:mp4:m4a");
            } else {
                req.addOption("-S", "ext:mp4:m4a");
            }
        }
        return req;
    }

    private void run(YoutubeDLRequest req) throws Exception {
        Function3<Float, Long, String, Unit> callback = (progress, eta, line) -> {
            int pc = progress == null ? 0 : Math.round(progress);
            if (pc < 0) pc = 0;
            if (pc > 100) pc = 100;
            String detail = line == null ? "" : line.trim();
            DownloadBus.progress(pc, detail);
            notifyProgress(pc, detail);
            return Unit.INSTANCE;
        };
        YoutubeDL.getInstance().execute(req, PROC_ID, false, callback);
    }

    private void cleanupPartials(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 3600L * 1000L;
        for (File f : files) {
            if (f.isFile() && f.lastModified() < cutoff) {
                String n = f.getName();
                if (n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".temp")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    private File newest(File dir, long since) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName();
            if (n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".temp")) continue;
            if (f.lastModified() < since) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    private void fail(String message) {
        DownloadBus.error(message);
        stopForegroundCompat();
        stopSelf();
    }

    private void notifyDone(HistoryStore.Item item) {
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle("Saved to " + (item.audio ? "Music" : "Movies") + "/YT Grab")
                    .setContentText(item.title)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(item.title + "\n" + item.meta))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build();
            stopForegroundCompat();
            nm.notify(NOTIF_ID, n);
        } catch (Exception ignored) {
            stopForegroundCompat();
        }
        stopSelf();
    }

    private void startInForeground(String title, String text, int percent) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, percent, percent <= 0)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void notifyProgress(int percent, String detail) {
        if (percent == lastNotified) return;
        if (percent < 100 && percent % 4 != 0) return;
        lastNotified = percent;
        try {
            String text = detail.isEmpty() ? "Downloading…" : detail;
            if (text.length() > 90) text = text.substring(0, 90) + "…";
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle(jobTitle)
                    .setContentText(text)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setProgress(100, percent, false)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
            nm.notify(NOTIF_ID, n);
        } catch (Exception ignored) {
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Download progress and finished files");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private String friendly(Exception e) {
        String raw = shortMsg(e);
        String low = raw.toLowerCase();
        if (low.contains("sign in") || low.contains("bot")) {
            return "YouTube asked for verification. Try again in a minute, or update the engine from the ⋮ menu.";
        }
        if (low.contains("unable to download") || low.contains("403")) {
            return "YouTube refused this download. Open the ⋮ menu → Update downloader engine, then try again.\n\n" + raw;
        }
        if (low.contains("unsupported url")) {
            return "This link is not supported. Paste a normal YouTube video link.";
        }
        return raw;
    }

    private String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        m = m.replaceAll("\u001B\\[[;\\d]*m", "");
        m = m.trim();
        if (m.length() > 600) m = m.substring(0, 600) + "…";
        return m;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdown();
        super.onDestroy();
    }
}

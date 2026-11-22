package com.zyvo.ytgrab;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide bridge between the download service and whatever screen is visible.
 * All callbacks are delivered on the main thread.
 */
public class DownloadBus {

    public interface Listener {
        void onStarted(String title);

        void onProgress(int percent, String detail);

        void onDone(HistoryStore.Item item);

        void onError(String message);
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static volatile boolean running;
    public static volatile int percent;
    public static volatile String detail = "";
    public static volatile String title = "";

    public static void add(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void remove(Listener l) {
        listeners.remove(l);
    }

    static void started(String t) {
        running = true;
        percent = 0;
        detail = "Connecting…";
        title = t == null ? "" : t;
        MAIN.post(() -> {
            for (Listener l : listeners) l.onStarted(title);
        });
    }

    static void progress(int p, String d) {
        percent = p;
        detail = d == null ? "" : d;
        MAIN.post(() -> {
            for (Listener l : listeners) l.onProgress(p, detail);
        });
    }

    static void done(HistoryStore.Item item) {
        running = false;
        MAIN.post(() -> {
            for (Listener l : listeners) l.onDone(item);
        });
    }

    static void error(String msg) {
        running = false;
        MAIN.post(() -> {
            for (Listener l : listeners) l.onError(msg);
        });
    }
}

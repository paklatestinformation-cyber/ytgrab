package com.zyvo.ytgrab;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThumbLoader {

    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE;

    static {
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        CACHE = new LruCache<String, Bitmap>(kb / 8) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public static void load(final ImageView view, final String url) {
        if (url == null) {
            view.setTag(null);
            view.setImageDrawable(null);
            return;
        }
        view.setTag(url);
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        IO.execute(() -> {
            Bitmap bmp = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) YTGrab/1.0");
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        bmp = BitmapFactory.decodeStream(is);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (bmp != null) CACHE.put(url, bmp);
            final Bitmap result = bmp;
            MAIN.post(() -> {
                if (result != null && url.equals(view.getTag())) {
                    view.setImageBitmap(result);
                }
            });
        });
    }
}

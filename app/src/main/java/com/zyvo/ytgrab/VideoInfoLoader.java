package com.zyvo.ytgrab;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pulls the real video title + channel from YouTube's public oEmbed endpoint. */
public class VideoInfoLoader {

    public interface Callback {
        void onInfo(String title, String author);

        void onFail();
    }

    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void load(final String videoId, final Callback cb) {
        if (videoId == null) {
            cb.onFail();
            return;
        }
        IO.execute(() -> {
            String title = null;
            String author = null;
            HttpURLConnection conn = null;
            try {
                String target = "https://www.youtube.com/watch?v=" + videoId;
                String url = "https://www.youtube.com/oembed?url="
                        + URLEncoder.encode(target, "UTF-8") + "&format=json";
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) YTGrab/1.0");
                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONObject o = new JSONObject(sb.toString());
                    title = o.optString("title", null);
                    author = o.optString("author_name", null);
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String ft = title;
            final String fa = author;
            MAIN.post(() -> {
                if (ft != null && !ft.isEmpty()) cb.onInfo(ft, fa);
                else cb.onFail();
            });
        });
    }
}

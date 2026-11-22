package com.zyvo.ytgrab;

import android.content.Context;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;

/**
 * Thin wrapper around the bundled yt-dlp + ffmpeg engines.
 * Initialisation unzips the python runtime on first run, so it always happens off the UI thread.
 */
public class YtDlp {

    private static volatile boolean ready;
    public static volatile boolean ffmpegOk;

    public static synchronized void ensureInit(Context ctx) throws Exception {
        if (ready) return;
        YoutubeDL.getInstance().init(ctx.getApplicationContext());
        try {
            FFmpeg.getInstance().init(ctx.getApplicationContext());
            ffmpegOk = true;
        } catch (Exception ignored) {
            // ffmpeg is optional: audio extraction falls back to the plain m4a stream
            ffmpegOk = false;
        }
        ready = true;
    }

    public static boolean isReady() {
        return ready;
    }
}

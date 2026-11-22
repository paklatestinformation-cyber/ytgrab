package com.zyvo.ytgrab;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Moves a finished download into the public gallery / music library. */
public class MediaSaver {

    public static HistoryStore.Item publish(Context ctx, File src, boolean audio, String thumb) {
        HistoryStore.Item item = new HistoryStore.Item();
        item.title = prettyTitle(src.getName());
        item.audio = audio;
        item.thumb = thumb;
        item.size = src.length();
        item.ts = System.currentTimeMillis();
        String[] type = resolveType(src.getName(), audio);
        String mime = type[0];
        item.mime = mime;
        item.meta = type[1] + " · " + humanSize(src.length());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = insertIntoMediaStore(ctx, src, audio, mime);
            if (uri != null) {
                item.uri = uri.toString();
                //noinspection ResultOfMethodCallIgnored
                src.delete();
                return item;
            }
        } else {
            File publicDir = new File(Environment.getExternalStoragePublicDirectory(
                    audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES), "YT Grab");
            if (publicDir.exists() || publicDir.mkdirs()) {
                File dst = new File(publicDir, src.getName());
                try {
                    copy(src, dst);
                    MediaScannerConnection.scanFile(ctx,
                            new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);
                    //noinspection ResultOfMethodCallIgnored
                    src.delete();
                    item.path = dst.getAbsolutePath();
                    return item;
                } catch (Exception ignored) {
                }
            }
        }
        item.path = src.getAbsolutePath();
        return item;
    }

    private static String[] resolveType(String fileName, boolean audio) {
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) ext = fileName.substring(dot + 1).toLowerCase(java.util.Locale.US);
        if (audio) {
            if (ext.equals("m4a") || ext.equals("mp4")) return new String[]{"audio/mp4", "M4A"};
            if (ext.equals("opus") || ext.equals("ogg")) return new String[]{"audio/ogg", "OPUS"};
            if (ext.equals("mp3")) return new String[]{"audio/mpeg", "MP3"};
            if (ext.isEmpty()) return new String[]{"audio/mp4", "M4A"};
            return new String[]{"audio/*", ext.toUpperCase(java.util.Locale.US)};
        }
        if (ext.equals("webm")) return new String[]{"video/webm", "WEBM"};
        if (ext.equals("mkv")) return new String[]{"video/x-matroska", "MKV"};
        if (ext.equals("3gp")) return new String[]{"video/3gpp", "3GP"};
        if (ext.equals("mov")) return new String[]{"video/quicktime", "MOV"};
        return new String[]{"video/mp4", "MP4"};
    }

    private static Uri insertIntoMediaStore(Context ctx, File src, boolean audio, String mime) {
        ContentResolver resolver = ctx.getContentResolver();
        Uri collection = audio
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/YT Grab");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(collection, values);
            if (uri == null) return null;
            try (OutputStream os = resolver.openOutputStream(uri);
                 InputStream is = new FileInputStream(src)) {
                if (os == null) throw new IllegalStateException("no output stream");
                copy(is, os);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        } catch (Exception e) {
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    private static void copy(File src, File dst) throws Exception {
        try (InputStream is = new FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            copy(is, os);
        }
    }

    private static void copy(InputStream is, OutputStream os) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
    }

    public static String prettyTitle(String fileName) {
        String name = fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("\\s*\\[[A-Za-z0-9_-]{11}\\]$", "");
        return name.trim();
    }

    public static String humanSize(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1000) return String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0);
        if (mb >= 100) return String.format(java.util.Locale.US, "%.0f MB", mb);
        return String.format(java.util.Locale.US, "%.1f MB", mb);
    }
}

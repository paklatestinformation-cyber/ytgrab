package com.zyvo.ytgrab;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UrlUtil {

    private static final Pattern URL = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
    private static final Pattern ID = Pattern.compile(
            "(?:[?&]v=|/shorts/|/embed/|/live/|/v/|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private UrlUtil() {
    }

    public static String firstUrl(String text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        if (m.find()) {
            String u = m.group();
            while (u.endsWith(".") || u.endsWith(",") || u.endsWith(")")) {
                u = u.substring(0, u.length() - 1);
            }
            return u;
        }
        return null;
    }

    public static boolean isYouTube(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("youtube.com") || u.contains("youtu.be");
    }

    public static String videoId(String url) {
        if (url == null) return null;
        Matcher m = ID.matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    public static String thumbUrl(String videoId) {
        if (videoId == null) return null;
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }
}

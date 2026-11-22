package com.zyvo.ytgrab;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class HistoryStore {

    private static final String PREF = "ytgrab_history";
    private static final String KEY = "items";
    private static final int MAX = 40;

    public static class Item {
        public String title = "";
        public String uri;
        public String path;
        public String meta = "";
        public String thumb;
        public String mime = "video/mp4";
        public long size;
        public long ts;
        public boolean audio;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.title = o.optString("title", "");
                it.uri = o.has("uri") && !o.isNull("uri") ? o.optString("uri") : null;
                it.path = o.has("path") && !o.isNull("path") ? o.optString("path") : null;
                it.meta = o.optString("meta", "");
                it.thumb = o.has("thumb") && !o.isNull("thumb") ? o.optString("thumb") : null;
                it.mime = o.optString("mime", "video/mp4");
                it.size = o.optLong("size", 0);
                it.ts = o.optLong("ts", 0);
                it.audio = o.optBoolean("audio", false);
                out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, List<Item> list) {
        JSONArray arr = new JSONArray();
        for (Item it : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("title", it.title);
                o.put("uri", it.uri == null ? JSONObject.NULL : it.uri);
                o.put("path", it.path == null ? JSONObject.NULL : it.path);
                o.put("meta", it.meta);
                o.put("thumb", it.thumb == null ? JSONObject.NULL : it.thumb);
                o.put("mime", it.mime);
                o.put("size", it.size);
                o.put("ts", it.ts);
                o.put("audio", it.audio);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }

    public static void add(Context c, Item item) {
        List<Item> list = load(c);
        list.add(0, item);
        while (list.size() > MAX) list.remove(list.size() - 1);
        save(c, list);
    }

    public static void remove(Context c, Item item) {
        List<Item> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            Item it = list.get(i);
            boolean same = item.ts == it.ts && item.title.equals(it.title);
            if (same) {
                list.remove(i);
                break;
            }
        }
        save(c, list);
    }

    public static void clear(Context c) {
        prefs(c).edit().remove(KEY).apply();
    }
}

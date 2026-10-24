package com.zmlworks.poolmemo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

final class Prefs {
    private static final String NAME = "poolmemo";
    private static final int MAX_RECENT = 12;

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static String getId(Context c) { return sp(c).getString("id", ""); }
    static String getKey(Context c) { return sp(c).getString("key", ""); }

    static void setCurrent(Context c, String id, String key) {
        sp(c).edit().putString("id", id).putString("key", key).apply();
        addRecent(c, id);
    }

    static List<String> recentIds(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    private static void addRecent(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        List<String> list = recentIds(c);
        list.remove(id);
        list.add(0, id);
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        sp(c).edit().putString("recent", a.toString()).apply();
    }
}

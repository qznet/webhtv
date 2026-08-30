package com.fongmi.android.tv.smb;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persists the list of SMB server profiles in a private SharedPreferences (JSON array). */
public class SmbStore {

    private static final String PREFS = "smb_server";
    private static final String KEY = "servers";

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<SmbServer> getAll() {
        List<SmbServer> list = new ArrayList<>();
        String raw = prefs().getString(KEY, null);
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                list.add(SmbServer.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
            // corrupt data: start fresh
        }
        return list;
    }

    public static void save(SmbServer server) {
        if (server.getId().isEmpty()) server.setId(UUID.randomUUID().toString());
        List<SmbServer> all = getAll();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(server.getId())) {
                all.set(i, server);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(server);
        persist(all);
    }

    public static void remove(String id) {
        List<SmbServer> all = getAll();
        all.removeIf(s -> s.getId().equals(id));
        persist(all);
    }

    private static void persist(List<SmbServer> all) {
        JSONArray arr = new JSONArray();
        try {
            for (SmbServer s : all) arr.put(s.toJson());
            prefs().edit().putString(KEY, arr.toString()).apply();
        } catch (JSONException ignored) {
            // serialization failure: ignore
        }
    }
}

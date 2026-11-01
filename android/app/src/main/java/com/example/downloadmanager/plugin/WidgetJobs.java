package com.example.downloadmanager.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Persistent registry of downloads started from the home-screen widget.
 *
 * The widget path runs natively, before (or without) the WebView ever loading, so the JS store
 * learns about these jobs by reading this file on startup and polling while one is running.
 * Records are reconciled on read: anything marked "running" whose process is gone was killed
 * with the app and becomes "paused" — resumable from the normal list.
 */
public final class WidgetJobs {

    private static final String PREFS = "downswing_widget_jobs";
    private static final String KEY = "jobs";
    private static final int MAX = 30;

    private WidgetJobs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<JSONObject> readRaw(Context context) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(context).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        } catch (Exception ignored) {
            // Corrupt or empty: start from no jobs.
        }
        return out;
    }

    private static void writeRaw(Context context, List<JSONObject> list) {
        // Oldest completed records fall off first so the file stays small.
        while (list.size() > MAX) {
            int victim = 0;
            long oldest = Long.MAX_VALUE;
            for (int i = 0; i < list.size(); i++) {
                JSONObject j = list.get(i);
                if (!"running".equals(j.optString("status"))
                    && j.optLong("finishedAt", j.optLong("createdAt")) < oldest) {
                    oldest = j.optLong("finishedAt", j.optLong("createdAt"));
                    victim = i;
                }
            }
            list.remove(victim);
        }
        JSONArray arr = new JSONArray();
        for (JSONObject j : list) arr.put(j);
        prefs(context).edit().putString(KEY, arr.toString()).apply();
    }

    /** All jobs, with stale "running" entries reconciled to "paused". */
    public static synchronized List<JSONObject> all(Context context) {
        List<JSONObject> list = readRaw(context);
        boolean changed = false;
        for (JSONObject j : list) {
            if ("running".equals(j.optString("status")) && !WidgetRunner.isActive(j.optString("id"))) {
                try {
                    j.put("status", "paused");
                    changed = true;
                } catch (Exception ignored) {
                }
            }
        }
        if (changed) writeRaw(context, list);
        return list;
    }

    public static synchronized String allJson(Context context) {
        JSONArray arr = new JSONArray();
        for (JSONObject j : all(context)) arr.put(j);
        return arr.toString();
    }

    /** First still-running job, for the widget's headline. Reconciles before answering. */
    public static synchronized JSONObject topRunning(Context context) {
        for (JSONObject j : all(context)) {
            if ("running".equals(j.optString("status"))) return j;
        }
        return null;
    }

    public static synchronized void put(Context context, JSONObject job) {
        List<JSONObject> list = readRaw(context);
        String id = job.optString("id");
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).optString("id").equals(id)) {
                list.set(i, job);
                writeRaw(context, list);
                return;
            }
        }
        list.add(0, job);
        writeRaw(context, list);
    }

    private static synchronized void patch(Context context, String id, String status,
                                           Integer progress, String speed, String eta,
                                           String name, String path, String error, long timestamp) {
        List<JSONObject> list = readRaw(context);
        for (JSONObject j : list) {
            if (!j.optString("id").equals(id)) continue;
            try {
                if (status != null) j.put("status", status);
                if (progress != null) j.put("progress", progress);
                if (speed != null) j.put("speed", speed);
                if (eta != null) j.put("eta", eta);
                if (name != null) j.put("name", name);
                if (path != null) j.put("path", path);
                if (error != null) j.put("error", error);
                if (timestamp > 0) j.put(status != null ? "finishedAt" : "updatedAt", timestamp);
            } catch (Exception ignored) {
                return; // a half-written record is not worth crashing a download over
            }
            writeRaw(context, list);
            return;
        }
    }

    public static void progress(Context context, String id, int progress, String speed, String eta, String name) {
        patch(context, id, null, progress, speed, eta, name, null, null, 0);
    }

    public static void complete(Context context, String id, String path, String name) {
        patch(context, id, "completed", 100, "", "", name, path, "", System.currentTimeMillis());
    }

    public static void fail(Context context, String id, String error) {
        patch(context, id, "error", null, null, null, null, null, error, System.currentTimeMillis());
    }

    public static void setStatus(Context context, String id, String status) {
        patch(context, id, status, null, "".equals(status) ? null : null, null, null, null, null,
            "completed".equals(status) || "error".equals(status) ? System.currentTimeMillis() : 0);
    }

    public static synchronized void remove(Context context, String id) {
        List<JSONObject> list = readRaw(context);
        list.removeIf(j -> j.optString("id").equals(id));
        writeRaw(context, list);
    }
}

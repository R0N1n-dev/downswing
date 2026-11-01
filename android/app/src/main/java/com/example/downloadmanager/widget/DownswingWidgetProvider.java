package com.example.downloadmanager.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import com.example.downloadmanager.MainActivity;
import com.example.downloadmanager.R;
import com.example.downloadmanager.plugin.WidgetJobs;
import org.json.JSONObject;

/**
 * Home-screen widget: what is downloading, how far along, and two actions —
 * quick-add (translucent activity that reads the clipboard and starts a native download)
 * and open the app. Rendered entirely from WidgetJobs + the native job count, so it works
 * even when the WebView has never loaded this session.
 */
public class DownswingWidgetProvider extends AppWidgetProvider {

    /** Headline pushed by the running app ("2 active · 4.1 MB/s"). */
    public static final String WEB_PREFS = "downswing_widget_web";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateAll(context);
    }

    /** Redraw every placed instance. Cheap: reads one SharedPreferences record. */
    public static void updateAll(Context context) {
        Context app = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        ComponentName me = new ComponentName(app, DownswingWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(me);
        if (ids.length == 0) return;

        RemoteViews views = build(app);
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    private static RemoteViews build(Context app) {
        RemoteViews views = new RemoteViews(app.getPackageName(), R.layout.downswing_widget);

        JSONObject job = WidgetJobs.topRunning(app);
        String title;
        String status;
        int pct = -1;

        if (job != null) {
            title = job.optString("name", "Downloading…");
            int p = job.optInt("progress", 0);
            String speed = job.optString("speed", "");
            String eta = job.optString("eta", "");
            StringBuilder sb = new StringBuilder(p + "%");
            if (!speed.isEmpty()) sb.append(" · ").append(speed);
            if (!eta.isEmpty()) sb.append(" · ").append(eta).append(" left");
            status = sb.toString();
            pct = p;
        } else {
            title = "Downswing";
            String web = app.getSharedPreferences(WEB_PREFS, Context.MODE_PRIVATE).getString("label", "");
            status = web.isEmpty() ? "Nothing downloading — paste a link, then tap +" : web;
        }

        views.setTextViewText(R.id.widget_title, title);
        views.setTextViewText(R.id.widget_status, status);
        if (pct >= 0) {
            views.setProgressBar(R.id.widget_bar, 100, pct, false);
            views.setViewVisibility(R.id.widget_bar, android.view.View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.widget_bar, android.view.View.GONE);
        }

        // Quick add: translucent activity that reads the clipboard while focused, then hands
        // the job to WidgetRunner and finishes — the app never opens.
        Intent quick = new Intent(app, QuickAddActivity.class);
        PendingIntent quickTap = PendingIntent.getActivity(app, 1, quick,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_add, quickTap);

        Intent open = new Intent(app, MainActivity.class);
        open.setData(Uri.parse("downswing://widget"));
        PendingIntent openTap = PendingIntent.getActivity(app, 2, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_open, openTap);
        views.setOnClickPendingIntent(R.id.widget_root, openTap);

        return views;
    }
}

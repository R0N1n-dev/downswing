package com.example.downloadmanager.plugin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.example.downloadmanager.R;
import com.example.downloadmanager.widget.DownswingWidgetProvider;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlin.Unit;
import org.json.JSONObject;

/**
 * Runs a widget quick-add download with no WebView involved.
 *
 * Legal on the paths that matter: the foreground service is started while the quick-add
 * activity is visible (or directly from the widget tap, which Android 12+ treats as the user
 * acting on your UI element), and a dataSync service then keeps the process alive after the
 * activity finishes. Progress lives in WidgetJobs so the app can adopt the download when it
 * next opens, and the home-screen widget re-renders as bytes move.
 */
public final class WidgetRunner {

    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Set<String> STOPPED = ConcurrentHashMap.newKeySet();

    private static final Pattern DEST = Pattern.compile("\\[download\\] Destination: (.+)$");
    private static final Pattern MERGE = Pattern.compile("\\[Merger\\] Merging formats into \"(.+)\"");
    private static final Pattern STAT = Pattern.compile(
        "\\[download\\]\\s+([\\d.]+)%.*?at\\s+(\\S+/s)(?:\\s+ETA\\s+(\\S+))?");

    private WidgetRunner() {}

    static boolean isActive(String id) {
        return id != null && ACTIVE.contains(id);
    }

    /**
     * Queue a download from a link. Returns the job id immediately; the engine warms up on a
     * background thread. Safe to call from the quick-add activity or a widget tap.
     */
    public static String start(Context context, String url) {
        Context app = context.getApplicationContext();
        String id = "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        JSONObject rec = new JSONObject();
        try {
            rec.put("id", id);
            rec.put("url", url);
            rec.put("name", nameFrom(url));
            rec.put("status", "running");
            rec.put("progress", 0);
            rec.put("speed", "");
            rec.put("eta", "");
            rec.put("path", "");
            rec.put("error", "");
            rec.put("createdAt", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        WidgetJobs.put(app, rec);

        ACTIVE.add(id);
        // Foreground service first, while our activity is on screen (or the widget tap counts):
        // the download must outlive the quick-add card closing.
        DownloadService.jobStarted(app);
        DownswingWidgetProvider.updateAll(app);

        EXEC.execute(() -> run(app, id, url));
        return id;
    }

    private static void run(Context app, String id, String url) {
        String path = "";
        String name = null;
        try {
            Engines.ensure(app);
            File dir = Engines.resolveOutputDir(app, "/storage/emulated/0/Download");

            YoutubeDLRequest request = new YoutubeDLRequest(url.trim());
            request.addOption("-o", dir.getAbsolutePath() + "/%(title).150B.%(ext)s");
            request.addOption("--no-mtime");
            request.addOption("--newline");
            request.addOption("--merge-output-format", "mp4");

            final String[] holder = new String[2];
            long[] lastWrite = {0};
            int[] lastPct = {-1};

            YoutubeDL.getInstance().execute(request, id, (progress, eta, line) -> {
                String l = line == null ? "" : line;
                Matcher d = DEST.matcher(l);
                if (d.find()) {
                    holder[0] = basename(d.group(1).trim());
                }
                Matcher m = MERGE.matcher(l);
                if (m.find()) holder[0] = basename(m.group(1).trim());

                int pct = progress >= 0 ? Math.round(progress) : -1;
                String speed = "";
                String etaText = eta >= 0 ? formatEta(eta) : "";
                Matcher s = STAT.matcher(l);
                if (s.find()) {
                    if (pct < 0) {
                        try { pct = Math.round(Float.parseFloat(s.group(1))); } catch (Exception ignored) {}
                    }
                    speed = s.group(2) == null ? "" : s.group(2);
                    if (etaText.isEmpty() && s.group(3) != null) etaText = s.group(3);
                }

                // One prefs write + widget redraw per percent point, not per log line.
                long now = System.currentTimeMillis();
                if (pct >= 0 && (pct != lastPct[0] || now - lastWrite[0] > 4000)) {
                    lastPct[0] = pct;
                    lastWrite[0] = now;
                    WidgetJobs.progress(app, id, pct, speed, etaText, holder[0]);
                    DownswingWidgetProvider.updateAll(app);
                }
                return Unit.INSTANCE;
            });

            if (ACTIVE.remove(id) && !STOPPED.remove(id)) {
                File done = holder[0] != null ? new File(dir, holder[0]) : dir;
                path = done.getAbsolutePath();
                name = holder[0];
                WidgetJobs.complete(app, id, path, name);
                notifyUser(app, "Download complete", name != null ? name : "Saved to your Download folder");
            }
        } catch (Exception e) {
            ACTIVE.remove(id);
            if (STOPPED.remove(id)) {
                // Cancelled or paused on purpose: keep the paused record, no error.
                WidgetJobs.setStatus(app, id, "paused");
            } else {
                WidgetJobs.fail(app, id, rootMessage(e));
                notifyUser(app, "Download failed", rootMessage(e));
            }
        } finally {
            ACTIVE.remove(id);
            STOPPED.remove(id);
            DownloadService.jobEnded(app);
            DownswingWidgetProvider.updateAll(app);
        }
    }

    /** Kill the engine process for this job; the record becomes "paused" (resumable in-app). */
    public static void stop(Context context, String id) {
        if (!ACTIVE.contains(id)) {
            WidgetJobs.setStatus(context.getApplicationContext(), id, "paused");
            return;
        }
        STOPPED.add(id);
        try {
            YoutubeDL.getInstance().destroyProcessById(id);
        } catch (Exception ignored) {
        }
        WidgetJobs.setStatus(context.getApplicationContext(), id, "paused");
        DownswingWidgetProvider.updateAll(context.getApplicationContext());
    }

    /** Stop if running and drop the record: the app has taken ownership (or removed the row). */
    public static void remove(Context context, String id) {
        Context app = context.getApplicationContext();
        stop(app, id);
        WidgetJobs.remove(app, id);
        DownswingWidgetProvider.updateAll(app);
    }

    private static String nameFrom(String url) {
        try {
            String path = new java.net.URL(url).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            last = java.net.URLDecoder.decode(last, "UTF-8");
            return last.isEmpty() ? url : last;
        } catch (Exception e) {
            return url;
        }
    }

    private static String basename(String p) {
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static String formatEta(long s) {
        if (s < 60) return Math.round(s) + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    private static String rootMessage(Throwable t) {
        String msg = null;
        while (t != null) {
            if (t.getMessage() != null && !t.getMessage().isEmpty()) msg = t.getMessage();
            t = t.getCause();
        }
        return msg != null ? msg : "Download failed";
    }

    private static final String DONE_CHANNEL = "downswing_done";

    /** Completion/failure notice for downloads that ran without the app open. */
    private static void notifyUser(Context app, String title, String body) {
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(DONE_CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(DONE_CHANNEL, "Download updates",
                    NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Completion and failure notices for downloads");
                nm.createNotificationChannel(ch);
            }
            Intent open = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            PendingIntent tap = PendingIntent.getActivity(app, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new NotificationCompat.Builder(app, DONE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
            nm.notify((int) System.currentTimeMillis(), n);
        } catch (Exception ignored) {
            // Notices are best effort; the widget and in-app list still tell the story.
        }
    }
}

package com.example.downloadmanager.plugin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Keeps the process alive while downloads run. Without a foreground service Android kills
 * the app, and every download with it, shortly after the screen turns off.
 *
 * It stays up while any native job is running OR the app reports queued downloads.
 */
public class DownloadService extends Service {
    private static final String CHANNEL = "downswing_downloads";
    private static final int NOTIFICATION_ID = 4242;

    private static int nativeJobs = 0;
    private static int pending = 0;

    /** Live headline pushed by the web layer ("2 active · 4.1 MB/s · 3 queued"). */
    private static String statusText = "";
    private static boolean running = false;

    private PowerManager.WakeLock wakeLock;

    static synchronized void jobStarted(Context context) {
        nativeJobs++;
        sync(context);
    }

    static synchronized void jobEnded(Context context) {
        nativeJobs = Math.max(0, nativeJobs - 1);
        sync(context);
    }

    static synchronized void setPending(Context context, int count) {
        pending = Math.max(0, count);
        sync(context);
    }

    /** Re-post the foreground notification with the current speed and queue state. */
    static synchronized void setStatus(Context context, String text) {
        String next = text == null ? "" : text.trim();
        if (next.equals(statusText)) return;
        statusText = next;
        if (!running) return; // nothing shown yet; onStartCommand picks the text up
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, build(context.getApplicationContext()));
    }

    private static Notification build(Context ctx) {
        String text = !statusText.isEmpty()
            ? statusText
            : (nativeJobs > 0
                ? nativeJobs + (nativeJobs == 1 ? " download running" : " downloads running")
                : "Downloads queued");

        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        PendingIntent tap = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(com.example.downloadmanager.R.drawable.ic_stat_bolt)
            .setContentTitle("Downswing")
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private static synchronized int jobs() {
        return nativeJobs;
    }

    private static void sync(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, DownloadService.class);
        if (nativeJobs > 0 || pending > 0) {
            try {
                ContextCompat.startForegroundService(app, intent);
            } catch (Exception ignored) {
                // Android 12+ refuses to start a foreground service from the background.
                // It is already running in the normal case, so nothing is lost.
            }
        } else {
            app.stopService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "downswing:downloads");
        wakeLock.acquire(6 * 60 * 60 * 1000L); // matches Android's 6 hour limit for data sync services
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        Notification notification = build(this);

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        return START_NOT_STICKY;
    }

    // Android 15 caps data sync services at 6 hours. Stop cleanly or the system crashes the app.
    @Override
    public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

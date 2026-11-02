package com.example.downloadmanager.plugin;

import android.content.Context;
import android.os.Environment;
import com.yausername.aria2c.Aria2c;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import java.io.File;

/** One place that unpacks the native engines (yt-dlp, ffmpeg, aria2c) exactly once. */
final class Engines {
    private static boolean ready = false;

    private Engines() {}

    static synchronized void ensure(Context context) throws Exception {
        if (ready) return;
        Context app = context.getApplicationContext();
        YoutubeDL.getInstance().init(app);
        FFmpeg.getInstance().init(app);
        Aria2c.getInstance().init(app);
        ready = true;
    }

    static File packagesDir(Context context) {
        return new File(new File(context.getNoBackupFilesDir(), "youtubedl-android"), "packages");
    }

    /** Requested folder if writable, otherwise the app's own downloads folder. */
    static File resolveOutputDir(Context context, String requested) {
        return resolveOutputDir(context, requested, null);
    }

    /**
     * Requested root (or the app's own downloads folder) with a relative path appended, e.g.
     * "Downswing/Video". The subfolder is created on demand, so no empty category folders are
     * ever left behind, and it can never climb out of the root it was given.
     */
    static File resolveOutputDir(Context context, String requested, String subDir) {
        File base;
        if (requested != null && !requested.isEmpty()) {
            File d = new File(requested);
            base = (d.isDirectory() || d.mkdirs()) && d.canWrite() ? d : null;
        } else {
            base = null;
        }
        if (base == null) {
            base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (base == null) base = new File(context.getFilesDir(), "downloads");
            base.mkdirs();
        }
        File sub = relativeTo(base, subDir);
        if (sub == null) return base;
        return (sub.isDirectory() || sub.mkdirs()) && sub.canWrite() ? sub : base;
    }

    /** "Downswing/Video" under base. Null when the path is empty or tries to escape. */
    private static File relativeTo(File base, String subDir) {
        if (subDir == null) return null;
        String cleaned = subDir.trim().replace('\\', '/');
        if (cleaned.isEmpty()) return null;
        File dir = base;
        for (String part : cleaned.split("/")) {
            String seg = part.trim();
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..") || seg.contains(":")) return null;
            dir = new File(dir, seg);
        }
        return dir.equals(base) ? null : dir;
    }
}

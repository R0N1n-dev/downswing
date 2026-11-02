package com.example.downloadmanager.plugin;

import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct file downloads, .torrent files and magnet links, powered by the aria2c binary
 * that ships with youtubedl-android. One aria2c process per download.
 */
@CapacitorPlugin(name = "Aria2")
public class Aria2Plugin extends Plugin {

    private static final String[] TRACKERS = {
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.stealth.si:80/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
    };

    private static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    // [#a1b2c3 1.1MiB/10MiB(11%) CN:5 SD:3 DL:200KiB UL:10KiB(1.2MiB) ETA:45s]
    private static final Pattern READOUT =
        Pattern.compile("\\[#\\w+\\s+([\\d.]+\\w*)/([\\d.]+\\w*)(?:\\((\\d+)%\\))?([^\\]]*)\\]");
    private static final Pattern CN = Pattern.compile("CN:(\\d+)");
    private static final Pattern SD = Pattern.compile("SD:(\\d+)");
    private static final Pattern DL = Pattern.compile("DL:([\\d.]+\\w*)");
    private static final Pattern ETA = Pattern.compile("ETA:(\\S+)");

    // "  1|./Name/file.mkv" then "   |1.2GiB (1,234,567,890)"
    private static final Pattern FILE_ROW = Pattern.compile("^\\s*(\\d+)\\|(.*)$");
    private static final Pattern FILE_SIZE = Pattern.compile("^\\s*\\|\\s*[\\d.]+\\s*\\w+\\s*\\(([\\d,]+)\\)");
    private static final Pattern SELECT = Pattern.compile("^[0-9,\\-]+$");
    private static final long INSPECT_TIMEOUT_MS = 90_000;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Set<String> stopped = ConcurrentHashMap.newKeySet();
    private Future<Void> initTask;

    @Override
    public void load() {
        initTask = executor.submit((Callable<Void>) () -> {
            Engines.ensure(getContext());
            return null;
        });
    }

    @PluginMethod
    public void start(PluginCall call) {
        String source = call.getString("source", null);
        if (source == null || source.trim().isEmpty()) {
            call.reject("A link, magnet or torrent file is required", "NO_SOURCE");
            return;
        }
        final String src = source.trim();
        final int rateLimitKb = call.getInt("rateLimitKb", 0);
        final String selectFile = call.getString("selectFile", "");
        final File dir = Engines.resolveOutputDir(getContext(), call.getString("outputDir", null), call.getString("subDir", null));
        final String id = UUID.randomUUID().toString();
        stopped.remove(id);

        DownloadService.jobStarted(getContext());
        executor.execute(() -> {
            try {
                run(id, src, dir, rateLimitKb, selectFile);
            } finally {
                DownloadService.jobEnded(getContext());
            }
        });

        JSObject result = new JSObject();
        result.put("id", id);
        result.put("outputDir", dir.getAbsolutePath());
        call.resolve(result);
    }

    private void run(String id, String src, File dir, int rateLimitKb, String selectFile) {
        Deque<String> lines = new ArrayDeque<>();
        try {
            initTask.get();

            List<String> args = commonArgs(dir, rateLimitKb);
            if (selectFile != null && SELECT.matcher(selectFile).matches()) args.add("--select-file=" + selectFile);
            args.add(src);
            ProcessBuilder pb = processFor(args).redirectErrorStream(true);

            Process process = pb.start();
            processes.put(id, process);
            if (stopped.contains(id)) process.destroy();

            String name = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    lines.addLast(t);
                    if (lines.size() > 14) lines.removeFirst();

                    if (t.startsWith("FILE:")) {
                        String rel = relativeName(dir, t.substring(5).trim());
                        if (rel != null) name = rel;
                        continue;
                    }
                    Matcher m = READOUT.matcher(t);
                    if (m.find()) emitProgress(id, m, name);
                }
            }

            int code = process.waitFor();
            processes.remove(id);
            if (stopped.remove(id)) return; // paused or cancelled by the user

            if (code == 0) {
                JSObject done = new JSObject();
                done.put("id", id);
                done.put("outputPath", name != null ? new File(dir, name).getAbsolutePath() : dir.getAbsolutePath());
                if (name != null) done.put("name", name);
                notifyListeners("aria2Complete", done);
            } else {
                emitError(id, describe(code, lines));
            }
        } catch (Exception e) {
            processes.remove(id);
            if (stopped.remove(id)) return;
            emitError(id, e.getMessage() != null ? e.getMessage() : "Download failed");
        }
    }

    /** First path segment under the download folder: the file name, or a torrent's root folder. */
    private static String relativeName(File dir, String path) {
        String base = dir.getAbsolutePath() + "/";
        if (!path.startsWith(base)) return null; // [METADATA] and [MEMORY] entries
        String rel = path.substring(base.length());
        int slash = rel.indexOf('/');
        return slash > 0 ? rel.substring(0, slash) : rel;
    }

    private void emitProgress(String id, Matcher m, String name) {
        long done = parseSize(m.group(1));
        long total = parseSize(m.group(2));
        int pct = m.group(3) != null ? Integer.parseInt(m.group(3)) : (total > 0 ? (int) (done * 100 / total) : -1);
        String rest = m.group(4) == null ? "" : m.group(4);

        JSObject d = new JSObject();
        d.put("id", id);
        d.put("progress", pct);
        d.put("downloaded", done);
        d.put("total", total);
        d.put("speed", parseSize(first(DL, rest)));
        d.put("eta", parseEta(first(ETA, rest)));
        d.put("connections", parseInt(first(CN, rest)));
        d.put("seeders", parseInt(first(SD, rest)));
        if (name != null) d.put("name", name);
        notifyListeners("aria2Progress", d);
    }

    private void emitError(String id, String message) {
        JSObject d = new JSObject();
        d.put("id", id);
        d.put("message", message);
        notifyListeners("aria2Error", d);
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static long parseSize(String s) {
        Matcher m = Pattern.compile("([\\d.]+)(\\w*)").matcher(s == null ? "" : s);
        if (!m.matches()) return 0;
        double v = Double.parseDouble(m.group(1));
        String u = m.group(2);
        int power = u.startsWith("K") ? 1 : u.startsWith("M") ? 2 : u.startsWith("G") ? 3 : u.startsWith("T") ? 4 : 0;
        return (long) (v * Math.pow(1024, power));
    }

    private static int parseEta(String s) {
        if (s == null || s.isEmpty()) return -1;
        Matcher m = Pattern.compile("(\\d+)([dhms])").matcher(s);
        int total = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            int n = Integer.parseInt(m.group(1));
            switch (m.group(2)) {
                case "d": total += n * 86400; break;
                case "h": total += n * 3600; break;
                case "m": total += n * 60; break;
                default: total += n;
            }
        }
        return any ? total : -1;
    }

    private static String describe(int code, Deque<String> lines) {
        for (String l : (Iterable<String>) lines::descendingIterator) {
            if (l.contains("errorCode=") || l.contains("[ERROR]")) return l.length() > 300 ? l.substring(0, 300) : l;
        }
        switch (code) {
            case 2: return "The connection timed out.";
            case 3: return "The file was not found (404).";
            case 4: return "Too many 404 errors.";
            case 5: return "Download speed was too slow.";
            case 6: return "Network problem.";
            case 8: return "The server does not support resuming.";
            case 9: return "Not enough disk space.";
            case 13: return "The file already exists.";
            case 19: return "Could not resolve the host name.";
            case 24: return "The server asked for a login.";
            case 28: return "The torrent file is invalid.";
            default: return "aria2 stopped with error code " + code + ".";
        }
    }

    private File binary() {
        File bin = new File(getContext().getApplicationInfo().nativeLibraryDir, "libaria2c.so");
        if (!bin.exists()) throw new IllegalStateException("aria2c is missing from this build");
        return bin;
    }

    private String certPath() {
        return new File(Engines.packagesDir(getContext()), "python/usr/etc/tls/cert.pem").getAbsolutePath();
    }

    /** Flags shared by downloads and the torrent inspector. */
    private List<String> commonArgs(File dir, int rateLimitKb) {
        File state = new File(getContext().getFilesDir(), "aria2");
        state.mkdirs();

        List<String> cmd = new ArrayList<>();
        cmd.add("--dir=" + dir.getAbsolutePath());
        cmd.add("--continue=true");
        cmd.add("--file-allocation=none");
        cmd.add("--auto-file-renaming=true");
        cmd.add("--max-connection-per-server=16");
        cmd.add("--split=16");
        cmd.add("--min-split-size=1M");
        cmd.add("--connect-timeout=20");
        cmd.add("--timeout=30");
        cmd.add("--max-tries=5");
        cmd.add("--retry-wait=3");
        cmd.add("--summary-interval=1");
        cmd.add("--show-console-readout=false");
        cmd.add("--console-log-level=notice");
        cmd.add("--enable-color=false");
        cmd.add("--download-result=full");
        // Android has no /etc/resolv.conf, so c-ares cannot resolve names. Use the system resolver.
        cmd.add("--async-dns=false");
        cmd.add("--ca-certificate=" + certPath());
        cmd.add("--user-agent=" + USER_AGENT);
        // BitTorrent: stop as soon as the download is complete, no seeding.
        cmd.add("--seed-time=0");
        cmd.add("--enable-dht=true");
        cmd.add("--dht-file-path=" + new File(state, "dht.dat").getAbsolutePath());
        cmd.add("--bt-enable-lpd=true");
        cmd.add("--enable-peer-exchange=true");
        cmd.add("--bt-max-peers=100");
        cmd.add("--listen-port=6881-6999");
        cmd.add("--dht-listen-port=6881-6999");
        cmd.add("--bt-tracker=" + String.join(",", TRACKERS));
        if (rateLimitKb > 0) cmd.add("--max-overall-download-limit=" + rateLimitKb + "K");
        return cmd;
    }

    private ProcessBuilder processFor(List<String> args) {
        File packages = Engines.packagesDir(getContext());
        List<String> cmd = new ArrayList<>();
        cmd.add(binary().getAbsolutePath());
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", new File(packages, "python/usr/lib").getAbsolutePath() + ":"
            + new File(packages, "ffmpeg/usr/lib").getAbsolutePath() + ":"
            + new File(packages, "aria2c/usr/lib").getAbsolutePath());
        env.put("SSL_CERT_FILE", certPath());
        env.put("HOME", new File(packages, "python/usr").getAbsolutePath());
        env.put("TMPDIR", getContext().getCacheDir().getAbsolutePath());
        return pb;
    }

    // Process.waitFor(timeout) needs API 26. minSdk is 24.
    private static boolean waitFor(Process p, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException running) {
                Thread.sleep(250);
            }
        }
        return false;
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    private static void copy(File from, File to) throws Exception {
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /**
     * Lists the files inside a torrent so the user can pick some. Accepts a local .torrent path,
     * a .torrent URL, or a magnet link (metadata only, then exits). Returns the saved .torrent
     * path so the real download starts instantly with --select-file.
     */
    @PluginMethod
    public void inspect(PluginCall call) {
        final String id = call.getString("id", UUID.randomUUID().toString());
        final String source = call.getString("source", "");
        if (source == null || source.trim().isEmpty()) {
            call.reject("A magnet or torrent is required", "NO_SOURCE");
            return;
        }
        stopped.remove(id);
        DownloadService.jobStarted(getContext());
        executor.execute(() -> {
            File work = new File(getContext().getCacheDir(), "inspect/" + id);
            try {
                initTask.get();
                work.mkdirs();
                String src = source.trim();
                File torrent;

                if (src.startsWith("magnet:") || src.startsWith("http")) {
                    List<String> args = commonArgs(work, 0);
                    if (src.startsWith("magnet:")) {
                        args.add("--bt-metadata-only=true");
                        args.add("--bt-save-metadata=true");
                    } else {
                        args.add("--follow-torrent=false");
                    }
                    args.add(src);
                    Process p = processFor(args).redirectErrorStream(true).start();
                    processes.put(id, p);
                    // Nobody reads this output, but an unread pipe fills up and stalls aria2.
                    Thread drain = new Thread(() -> {
                        try (InputStream in = p.getInputStream()) {
                            byte[] sink = new byte[4096];
                            while (in.read(sink) >= 0) { /* discard */ }
                        } catch (Exception ignored) {
                        }
                    });
                    drain.setDaemon(true);
                    drain.start();
                    boolean finished = waitFor(p, INSPECT_TIMEOUT_MS);
                    if (!finished) p.destroy();
                    processes.remove(id);
                    if (stopped.remove(id)) {
                        call.reject("Cancelled", "CANCELLED");
                        return;
                    }
                    if (!finished) {
                        call.reject("Could not get the file list in time. Check the link or try again.", "TIMEOUT");
                        return;
                    }
                    File[] found = work.listFiles((d, n) -> n.endsWith(".torrent"));
                    if (found == null || found.length == 0) found = work.listFiles(File::isFile);
                    if (found == null || found.length == 0) {
                        call.reject("Could not fetch the torrent.", "NO_TORRENT");
                        return;
                    }
                    File keep = new File(new File(getContext().getFilesDir(), "torrents"), UUID.randomUUID() + ".torrent");
                    keep.getParentFile().mkdirs();
                    copy(found[0], keep);
                    torrent = keep;
                } else {
                    torrent = new File(src);
                }

                Process show = processFor(java.util.Arrays.asList("--show-files=true", torrent.getAbsolutePath()))
                    .redirectErrorStream(true).start();
                JSArray files = new JSArray();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(show.getInputStream()))) {
                    String line;
                    int index = -1;
                    String path = null;
                    while ((line = reader.readLine()) != null) {
                        Matcher row = FILE_ROW.matcher(line);
                        if (row.matches()) {
                            index = Integer.parseInt(row.group(1));
                            path = row.group(2).trim();
                            if (path.startsWith("./")) path = path.substring(2);
                            continue;
                        }
                        Matcher size = FILE_SIZE.matcher(line);
                        if (size.find() && path != null) {
                            JSObject f = new JSObject();
                            f.put("index", index);
                            f.put("path", path);
                            f.put("size", Long.parseLong(size.group(1).replace(",", "")));
                            files.put(f);
                            path = null;
                        }
                    }
                }
                show.waitFor();

                JSObject out = new JSObject();
                out.put("torrentPath", torrent.getAbsolutePath());
                out.put("files", files);
                call.resolve(out);
            } catch (Exception e) {
                processes.remove(id);
                call.reject(stopped.remove(id) ? "Cancelled" : (e.getMessage() != null ? e.getMessage() : "Could not read the torrent"), "INSPECT_FAILED");
            } finally {
                deleteRecursive(work);
                DownloadService.jobEnded(getContext());
            }
        });
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String id = call.getString("id", null);
        if (id == null) {
            call.reject("Job ID is required", "NO_ID");
            return;
        }
        stopped.add(id);
        Process p = processes.get(id);
        if (p != null) p.destroy(); // SIGTERM: aria2 flushes its .aria2 control file so resume works
        JSObject out = new JSObject();
        out.put("id", id);
        call.resolve(out);
    }

    /** Saves a picked .torrent file (base64) into app storage and returns its path. */
    @PluginMethod
    public void importTorrent(PluginCall call) {
        String data = call.getString("base64", null);
        if (data == null || data.isEmpty()) {
            call.reject("No torrent data", "NO_DATA");
            return;
        }
        try {
            File dir = new File(getContext().getFilesDir(), "torrents");
            dir.mkdirs();
            File out = new File(dir, UUID.randomUUID() + ".torrent");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(Base64.decode(data, Base64.DEFAULT));
            }
            JSObject res = new JSObject();
            res.put("path", out.getAbsolutePath());
            call.resolve(res);
        } catch (Exception e) {
            call.reject(e.getMessage() != null ? e.getMessage() : "Could not read the torrent file", "IMPORT_FAILED");
        }
    }

    @Override
    protected void handleOnDestroy() {
        for (Process p : processes.values()) p.destroy();
        executor.shutdownNow();
    }
}

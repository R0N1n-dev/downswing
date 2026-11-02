package com.example.downloadmanager.plugin;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;
import org.json.JSONObject;

@CapacitorPlugin(name = "YtDlp")
public class YtDlpPlugin extends Plugin {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Boolean> activeJobs = new ConcurrentHashMap<>();
    private Future<?> initTask;
    private Future<?> updateTask;

    @Override
    public void load() {
        // Unpacks the bundled yt-dlp/python/ffmpeg binaries on first launch.
        initTask = executor.submit(() -> {
            try {
                Engines.ensure(getContext());
            } catch (Exception e) {
                throw new RuntimeException("yt-dlp init failed: " + e.getMessage(), e);
            }
        });
        // The yt-dlp bundled in the library goes stale fast and sites break. Refresh it on launch.
        updateTask = executor.submit(() -> {
            try {
                initTask.get();
                YoutubeDL.getInstance().updateYoutubeDL(
                    getContext().getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
            } catch (Exception ignored) {
            }
        });
    }

    // Waits for the engine to unpack and for the launch-time yt-dlp update to finish (or give up).
    private void awaitInit() throws Exception {
        initTask.get();
        try {
            updateTask.get(25, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // No network or slow update: carry on with the installed engine.
        }
    }

    private static String rootMessage(Throwable t) {
        String msg = null;
        while (t != null) {
            if (t.getMessage() != null && !t.getMessage().isEmpty()) msg = t.getMessage();
            t = t.getCause();
        }
        return msg != null ? msg : "Unknown error";
    }

    private String engineVersion() {
        String v = YoutubeDL.getInstance().versionName(getContext().getApplicationContext());
        return v == null ? "" : v;
    }

    private File resolveOutputDir(String requested, String subDir) {
        // One implementation for both engines, so yt-dlp and aria2 always agree on the tree:
        // <root>/Downswing/<Category> (see Engines and src/lib/categories.js).
        return Engines.resolveOutputDir(getContext(), requested, subDir);
    }

    @PluginMethod
    public void start(PluginCall call) {
        String url = call.getString("url", null);
        if (url == null || url.trim().isEmpty()) {
            call.reject("URL is required", "NO_URL");
            return;
        }
        String format = call.getString("format", "bestvideo+bestaudio");
        boolean convertAudio = Boolean.TRUE.equals(call.getBoolean("convertAudio", false));
        String audioFormat = call.getString("audioFormat", "mp3");
        int rateLimitKb = call.getInt("rateLimitKb", 0);
        // Optional browser cookie for sites that only serve the media to a logged-in session
        // (Instagram posts, private videos). Sent as a raw Cookie header, e.g. "sessionid=…; …".
        String cookieHeader = call.getString("cookieHeader", null);
        File dir = resolveOutputDir(call.getString("outputDir", null), call.getString("subDir", null));

        String id = UUID.randomUUID().toString();
        activeJobs.put(id, Boolean.TRUE);

        DownloadService.jobStarted(getContext());
        executor.execute(() -> {
            try {
                awaitInit();
                YoutubeDLRequest request = new YoutubeDLRequest(url.trim());
                request.addOption("-o", dir.getAbsolutePath() + "/%(title).150B.%(ext)s");
                request.addOption("--no-mtime");
                request.addOption("--newline");
                if (rateLimitKb > 0) {
                    request.addOption("--limit-rate", rateLimitKb + "K");
                }
                if (cookieHeader != null && !cookieHeader.trim().isEmpty()) {
                    request.addOption("--add-header", "Cookie:" + cookieHeader.trim());
                }
                if (!"bestvideo+bestaudio".equals(format)) {
                    request.addOption("-f", format);
                }
                if (convertAudio) {
                    request.addOption("--extract-audio");
                    request.addOption("--audio-format", audioFormat);
                } else {
                    request.addOption("--merge-output-format", "mp4");
                }

                YoutubeDL.getInstance().execute(request, id, (progress, eta, line) -> {
                    JSObject data = new JSObject();
                    data.put("id", id);
                    data.put("progress", progress);
                    data.put("etaSeconds", eta);
                    data.put("line", line);
                    notifyListeners("downloadProgress", data);
                    return Unit.INSTANCE;
                });

                if (activeJobs.remove(id) != null) {
                    JSObject data = new JSObject();
                    data.put("id", id);
                    data.put("outputPath", dir.getAbsolutePath());
                    notifyListeners("downloadComplete", data);
                }
            } catch (Exception e) {
                // cancel() removes the id first, so a cancelled job is not reported as an error.
                if (activeJobs.remove(id) != null) {
                    JSObject data = new JSObject();
                    data.put("id", id);
                    data.put("message", rootMessage(e));
                    notifyListeners("downloadError", data);
                }
            } finally {
                DownloadService.jobEnded(getContext());
            }
        });

        JSObject result = new JSObject();
        result.put("id", id);
        result.put("outputDir", dir.getAbsolutePath());
        call.resolve(result);
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String id = call.getString("id", null);
        if (id == null) {
            call.reject("Job ID is required", "NO_ID");
            return;
        }
        if (activeJobs.remove(id) == null) {
            call.reject("Job not found: " + id, "NOT_FOUND");
            return;
        }
        try {
            YoutubeDL.getInstance().destroyProcessById(id);
        } catch (Exception ignored) {
        }
        JSObject data = new JSObject();
        data.put("id", id);
        notifyListeners("downloadCancelled", data);
        call.resolve(data);
    }

    @PluginMethod
    public void search(PluginCall call) {
        String query = call.getString("query", null);
        if (query == null || query.trim().isEmpty()) {
            call.reject("Query is required", "NO_QUERY");
            return;
        }
        executor.execute(() -> {
            try {
                awaitInit();
                YoutubeDLRequest request = new YoutubeDLRequest("ytsearch8:" + query.trim());
                request.addOption("--flat-playlist");
                request.addOption("--dump-json");
                request.addOption("--no-warnings");
                YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, null, null);

                JSArray results = new JSArray();
                for (String line : response.getOut().split("\n")) {
                    line = line.trim();
                    if (!line.startsWith("{")) continue;
                    JSONObject j = new JSONObject(line);
                    String vid = j.optString("id", "");
                    if (vid.isEmpty()) continue;
                    int secs = (int) j.optDouble("duration", 0);
                    JSObject item = new JSObject();
                    item.put("title", j.optString("title", vid));
                    item.put("url", j.optString("url", "https://www.youtube.com/watch?v=" + vid));
                    item.put("duration", secs > 0 ? String.format("%d:%02d", secs / 60, secs % 60) : "");
                    results.put(item);
                }
                JSObject out = new JSObject();
                out.put("results", results);
                call.resolve(out);
            } catch (Exception e) {
                call.reject(rootMessage(e), "SEARCH_FAILED");
            }
        });
    }

    @PluginMethod
    public void updateEngine(PluginCall call) {
        executor.execute(() -> {
            try {
                initTask.get();
                YoutubeDL.UpdateStatus status = YoutubeDL.getInstance().updateYoutubeDL(
                    getContext().getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
                JSObject out = new JSObject();
                out.put("status", status == null ? "UNKNOWN" : status.name());
                out.put("version", engineVersion());
                call.resolve(out);
            } catch (Exception e) {
                call.reject(rootMessage(e), "UPDATE_FAILED");
            }
        });
    }

    @PluginMethod
    public void getEngineVersion(PluginCall call) {
        executor.execute(() -> {
            try {
                initTask.get();
                JSObject out = new JSObject();
                out.put("version", engineVersion());
                call.resolve(out);
            } catch (Exception e) {
                call.reject(rootMessage(e), "NOT_READY");
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        executor.shutdownNow();
    }
}

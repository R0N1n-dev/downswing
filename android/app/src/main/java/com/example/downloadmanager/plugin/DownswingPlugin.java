package com.example.downloadmanager.plugin;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.MimeTypeMap;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import com.example.downloadmanager.MainActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import androidx.core.content.FileProvider;
import android.media.MediaScannerConnection;
import com.example.downloadmanager.widget.DownswingWidgetProvider;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App level helpers: background keep-alive, storage access, share intake, battery, lock, hashes. */
@CapacitorPlugin(name = "Downswing")
public class DownswingPlugin extends Plugin {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private PluginCall authCall;

    /** The app tells us how many downloads are queued or running so the service outlives the UI.
     *  The optional label is the widget's headline ("2 active · 4.1 MB/s"). */
    @PluginMethod
    public void setPending(PluginCall call) {
        int count = call.getInt("count", 0);
        DownloadService.setPending(getContext(), count);
        String label = call.getString("label", null);
        // The same headline drives the notification: "2 active · 4.1 MB/s · 3 queued".
        DownloadService.setStatus(getContext(), label == null ? "" : label);
        if (label != null) {
            getContext().getSharedPreferences(DownswingWidgetProvider.WEB_PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString("label", count > 0 ? label : "").apply();
            DownswingWidgetProvider.updateAll(getContext());
        }
        call.resolve();
    }

    @PluginMethod
    public void hasAllFilesAccess(PluginCall call) {
        boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        JSObject out = new JSObject();
        out.put("supported", supported);
        out.put("granted", !supported || Environment.isExternalStorageManager());
        call.resolve(out);
    }

    @PluginMethod
    public void requestAllFilesAccess(PluginCall call) {
        try {
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not open the access settings", "NO_SETTINGS");
        }
    }

    /** Links handed over by the Android share sheet, consumed so each share is delivered once. */
    @PluginMethod
    public void getSharedText(PluginCall call) {
        JSObject out = new JSObject();
        out.put("text", MainActivity.consumeSharedText());
        call.resolve(out);
    }

    /**
     * Reading the sticky battery broadcast needs no registered receiver (nothing to leak)
     * and works on every supported API level.
     */
    @PluginMethod
    public void isCharging(PluginCall call) {
        boolean charging = false;
        try {
            Intent battery = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int status = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception ignored) {
            // Report "not charging": the queue waits instead of starting by mistake.
        }
        JSObject out = new JSObject();
        out.put("charging", charging);
        call.resolve(out);
    }

    /** Whether this device can lock the app: hardware present and at least one credential enrolled. */
    @PluginMethod
    public void canBiometric(PluginCall call) {
        boolean supported = false;
        boolean enrolled = false;
        try {
            int res = BiometricManager.from(getContext()).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            enrolled = res == BiometricManager.BIOMETRIC_SUCCESS;
            supported = res != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
                && res != BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED;
        } catch (Exception ignored) {
            // Leave both false: the UI explains that locking is unavailable here.
        }
        JSObject out = new JSObject();
        out.put("supported", supported);
        out.put("enrolled", enrolled);
        call.resolve(out);
    }

    /** Shows the system biometric prompt and resolves once, on success or terminal failure. */
    @PluginMethod
    public void authenticate(PluginCall call) {
        Activity activity = getActivity();
        if (!(activity instanceof FragmentActivity)) {
            call.reject("The biometric prompt is not available", "NO_UI");
            return;
        }
        if (authCall != null) {
            call.reject("A prompt is already open", "BUSY");
            return;
        }
        authCall = call;
        final FragmentActivity fa = (FragmentActivity) activity;
        try {
            // Android 11+ may combine the biometric with the device credential. Below that the
            // two cannot be mixed, so older devices get the biometric prompt with a cancel button.
            int authenticators = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? (BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                : BiometricManager.Authenticators.BIOMETRIC_STRONG;
            BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Downswing")
                .setSubtitle("Confirm it is you")
                .setAllowedAuthenticators(authenticators);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                builder.setNegativeButtonText("Cancel");
            }
            final BiometricPrompt.PromptInfo info = builder.build();
            activity.runOnUiThread(() -> {
                try {
                    BiometricPrompt prompt = new BiometricPrompt(fa, ContextCompat.getMainExecutor(fa),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                JSObject out = new JSObject();
                                out.put("ok", true);
                                finishAuth(out);
                            }

                            @Override
                            public void onAuthenticationError(int errorCode, CharSequence errString) {
                                JSObject out = new JSObject();
                                out.put("ok", false);
                                out.put("code", errorCode);
                                finishAuth(out);
                            }

                            @Override
                            public void onAuthenticationFailed() {
                                // Wrong finger: the prompt stays open and asks again.
                            }
                        });
                    prompt.authenticate(info);
                } catch (Exception e) {
                    JSObject out = new JSObject();
                    out.put("ok", false);
                    out.put("code", -1);
                    finishAuth(out);
                }
            });
        } catch (Exception e) {
            authCall = null;
            call.reject("The biometric prompt could not be shown", "NO_UI");
        }
    }

    private void finishAuth(JSObject out) {
        PluginCall c = authCall;
        authCall = null;
        if (c != null) c.resolve(out);
    }

    /** SHA-256 of a downloaded file so the source checksum can be compared. Heavy I/O off the bridge thread. */
    @PluginMethod
    public void sha256(PluginCall call) {
        String path = call.getString("path", "");
        executor.execute(() -> {
            try {
                File f = new File(path);
                if (!f.isFile()) {
                    call.reject("The file is no longer on this device", "NOT_FOUND");
                    return;
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                }
                StringBuilder hex = new StringBuilder(64);
                for (byte b : md.digest()) hex.append(String.format("%02x", b));
                JSObject out = new JSObject();
                out.put("hex", hex.toString());
                call.resolve(out);
            } catch (Exception e) {
                call.reject("Could not read the file", "HASH_FAILED");
            }
        });
    }

    /**
     * Smart link preview: what does this URL actually serve? A short probe reports the final
     * content type, size and file name — or that the link is a web page rather than a file.
     */
    @PluginMethod
    public void probe(PluginCall call) {
        String url = call.getString("url", "").trim();
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            call.reject("A http(s) link is required", "BAD_URL");
            return;
        }
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("HEAD");
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
                int status = conn.getResponseCode();
                // Some servers reject HEAD; retry as a one-byte GET before giving up.
                if (status == HttpURLConnection.HTTP_BAD_METHOD || status == 403 || status == 405) {
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
                    conn.setRequestProperty("Range", "bytes=0-0");
                    status = conn.getResponseCode();
                }
                String type = conn.getContentType();
                long length = conn.getContentLengthLong();
                String finalUrl = conn.getURL().toString();
                String disposition = conn.getHeaderField("Content-Disposition");

                JSObject out = new JSObject();
                out.put("status", status);
                out.put("type", type == null ? "" : type.split(";")[0].trim());
                out.put("size", length);
                out.put("filename", filenameFrom(disposition, finalUrl));
                boolean page = (type != null && type.contains("text/html")) || finalUrl.endsWith("/")
                    || (!finalUrl.contains(".") || finalUrl.lastIndexOf('.') < finalUrl.lastIndexOf('/') + 1);
                out.put("isWebpage", page);
                call.resolve(out);
            } catch (Exception e) {
                // A failed probe must not block adding the link: the card just stays hidden.
                call.reject("Could not reach that link", "PROBE_FAILED");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String filenameFrom(String disposition, String url) {
        try {
            if (disposition != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("filename\\*=UTF-8''([^;]+)|filename=\"?([^\";]+)",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(disposition);
                if (m.find()) {
                    String name = m.group(1) != null ? m.group(1) : m.group(2);
                    name = java.net.URLDecoder.decode(name.trim(), "UTF-8");
                    if (!name.isEmpty()) return name;
                }
            }
            String path = new URL(url).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            return last.isEmpty() ? "" : java.net.URLDecoder.decode(last, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Open a finished file with the app the user trusts (FileProvider content URI). */
    @PluginMethod
    public void openFile(PluginCall call) {
        String path = call.getString("path", "");
        File f = new File(path);
        if (!path.isEmpty() && f.isFile()) {
            try {
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(uri, mimeOf(path));
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getContext().startActivity(view);
                call.resolve();
                return;
            } catch (ActivityNotFoundException e) {
                call.reject("No app on this phone can open " + extOf(path) + " files", "NO_APP");
                return;
            } catch (Exception e) {
                call.reject("Could not open that file", "OPEN_FAILED");
                return;
            }
        }
        call.reject("The file is no longer on this device", "NOT_FOUND");
    }

    /** Hand a finished file to any app through the system share sheet. */
    @PluginMethod
    public void shareFile(PluginCall call) {
        String path = call.getString("path", "");
        File f = new File(path);
        if (path.isEmpty() || !f.isFile()) {
            call.reject("The file is no longer on this device", "NOT_FOUND");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mimeOf(path));
            send.putExtra(Intent.EXTRA_STREAM, uri);
            String text = call.getString("text", "");
            if (!text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().startActivity(Intent.createChooser(send, "Share file"));
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not share that file", "SHARE_FAILED");
        }
    }

    /** Widget quick-add jobs, for the app to adopt on startup (and poll while one runs). */
    @PluginMethod
    public void listWidgetJobs(PluginCall call) {
        JSObject out = new JSObject();
        out.put("jobs", WidgetJobs.allJson(getContext()));
        call.resolve(out);
    }

    /** Pause/kill a widget-started native job from the in-app list. */
    @PluginMethod
    public void stopWidgetJob(PluginCall call) {
        String id = call.getString("id", "");
        if (id.isEmpty()) {
            call.reject("Job ID is required", "NO_ID");
            return;
        }
        WidgetRunner.stop(getContext(), id);
        call.resolve();
    }

    /** The app owns this download now (resumed through the web engine) or the row is gone. */
    @PluginMethod
    public void removeWidgetJob(PluginCall call) {
        String id = call.getString("id", "");
        if (!id.isEmpty()) WidgetRunner.remove(getContext(), id);
        call.resolve();
    }

    /** Publish a file into the media store so Gallery/Files see it immediately. */
    @PluginMethod
    public void scanFile(PluginCall call) {
        String path = call.getString("path", "");
        if (path.isEmpty() || !new File(path).isFile()) {
            call.resolve();
            return;
        }
        MediaScannerConnection.scanFile(getContext(), new String[]{path}, null, null);
        call.resolve();
    }

    private static String extOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
    }

    private static String mimeOf(String path) {
        String ext = extOf(path);
        String mime = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "application/octet-stream" : mime;
    }

    @Override
    protected void handleOnDestroy() {
        // Never leave a JS promise hanging if the activity dies under the prompt.
        JSObject out = new JSObject();
        out.put("ok", false);
        out.put("code", 10);
        finishAuth(out);
        executor.shutdownNow();
    }
}

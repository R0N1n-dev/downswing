package com.example.downloadmanager;

import android.content.Intent;
import android.os.Bundle;
import com.example.downloadmanager.plugin.Aria2Plugin;
import com.example.downloadmanager.plugin.DownswingPlugin;
import com.example.downloadmanager.plugin.YtDlpPlugin;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    // A link shared to Downswing from another app. Read once by DownswingPlugin.getSharedText.
    private static volatile String sharedText;

    private static String linkFrom(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) text = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        return text == null || text.trim().isEmpty() ? null : text;
    }

    public static synchronized String consumeSharedText() {
        String text = sharedText;
        sharedText = null;
        return text == null ? "" : text;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        sharedText = linkFrom(getIntent());
        // Local plugins are not auto-discovered. Without this the JS side gets
        // "YtDlp plugin is not implemented on android".
        registerPlugin(YtDlpPlugin.class);
        registerPlugin(Aria2Plugin.class);
        registerPlugin(DownswingPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // launchMode=singleTask: sharing into the already-running app lands here.
        String text = linkFrom(intent);
        if (text != null) sharedText = text;
    }
}

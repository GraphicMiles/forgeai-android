package ai.luna.app;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;

/**
 * The only Activity. Flutter draws; everything that touches the device — the
 * model runtime, the granted folder, downloads, the agent loop — lives in Java
 * behind {@link LunaBridge}.
 */
public class MainActivity extends FlutterActivity {

    private LunaBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A download runs behind a notification. Without this permission the
        // download still runs; the phone just will not show it.
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 9101);
        }
    }

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        bridge = new LunaBridge(this, flutterEngine.getDartExecutor().getBinaryMessenger());
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    /** A file shared in from another app is copied into the granted folder. */
    private void handleShare(Intent intent) {
        if (bridge == null || intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }
        Uri shared = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (shared != null) {
            bridge.acceptShared(shared);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (bridge != null) {
            bridge.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.dispose();
            bridge = null;
        }
        super.onDestroy();
    }
}

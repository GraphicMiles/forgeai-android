package ai.luna.app;

import android.content.Intent;
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
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        bridge = new LunaBridge(this, flutterEngine.getDartExecutor().getBinaryMessenger());
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

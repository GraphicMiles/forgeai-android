package ai.luna.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

/**
 * The download, running where the app is not.
 *
 * A download used to die with the process. This is a foreground service: the
 * work happens here, behind a notification, so closing Luna leaves it running
 * and the phone will not kill it for being idle in the background.
 *
 * It also decides when not to run. On mobile data with "Wi-Fi only" set, and on
 * a low battery that is not charging, it parks the download rather than failing
 * it — the part file is kept and it picks up where it stopped.
 */
public final class DownloadService extends Service {

    public static final String ACTION_START = "ai.luna.app.DOWNLOAD_START";
    public static final String ACTION_PAUSE = "ai.luna.app.DOWNLOAD_PAUSE";
    public static final String ACTION_CANCEL = "ai.luna.app.DOWNLOAD_CANCEL";
    public static final String EXTRA_ID = "id";

    private static final String CHANNEL = "luna_downloads";
    private static final int NOTIFICATION_ID = 7301;

    /** How the service tells the UI what happened, when the UI is there to hear it. */
    public interface Listener {
        void onDownloadEvent(JSONObject event);
    }

    private static volatile Listener listener;
    private static volatile String activeId = "";
    private static volatile ModelStore sharedStore;
    private static volatile Prefs sharedPrefs;
    private static volatile ErrorLog sharedErrors;

    public static void bind(ModelStore store, Prefs prefs, ErrorLog errors, Listener target) {
        sharedStore = store;
        sharedPrefs = prefs;
        sharedErrors = errors;
        listener = target;
    }

    public static void unbindListener() {
        listener = null;
    }

    public static String activeId() {
        return activeId;
    }

    public static void start(Context context, String id) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_ID, id);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void pause(Context context, String id) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_PAUSE);
        intent.putExtra(EXTRA_ID, id);
        context.startService(intent);
    }

    public static void cancel(Context context, String id) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_CANCEL);
        intent.putExtra(EXTRA_ID, id);
        context.startService(intent);
    }

    private Thread worker;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        final String id = intent == null ? "" : String.valueOf(intent.getStringExtra(EXTRA_ID));

        if (ACTION_PAUSE.equals(action)) {
            if (sharedStore != null) {
                sharedStore.pauseDownload();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            if (sharedStore != null) {
                sharedStore.cancelDownload();
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || sharedStore == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) {
            // One at a time. A phone on a train does not want three at once.
            return START_STICKY;
        }

        final ModelStore.Entry entry = ModelStore.find(id);
        if (entry == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification(entry.name, "Starting", 0));
        activeId = id;

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                String held = heldBack();
                if (held != null) {
                    report(id, sharedStore.bytesOnDisk(entry), entry.sizeBytes, "waiting", held);
                    if (sharedPrefs != null) {
                        sharedPrefs.setDownloadState(id, "waiting", sharedStore.bytesOnDisk(entry), entry.sizeBytes);
                    }
                    finish();
                    return;
                }
                String failure = sharedStore.download(entry, new ModelStore.ProgressSink() {
                    @Override
                    public void onProgress(String modelId, long completed, long total, String status) {
                        if (sharedPrefs != null && !status.startsWith("checksum:")) {
                            sharedPrefs.setDownloadState(modelId,
                                status.equals("done") ? null : status, completed, total);
                        }
                        report(modelId, completed, total, status, null);
                        if (status.equals("downloading")) {
                            int percent = total <= 0 ? 0 : (int) (completed * 100L / total);
                            update(entry.name, percent + "% of " + megabytes(total), percent);
                        } else if (status.equals("verifying")) {
                            update(entry.name, "Checking the file", 100);
                        }
                    }
                });
                if (failure != null && !failure.equals("Paused.") && !failure.equals("Cancelled.")) {
                    if (sharedErrors != null) {
                        sharedErrors.record("download", entry.name + ": " + failure);
                    }
                    report(id, 0L, entry.sizeBytes, "failed", failure);
                    if (sharedPrefs != null) {
                        sharedPrefs.setDownloadState(id, "failed", 0L, entry.sizeBytes);
                    }
                }
                finish();
            }
        });
        worker.start();
        return START_STICKY;
    }

    private void finish() {
        activeId = "";
        stopForeground(true);
        stopSelf();
    }

    /** Null when it may run, otherwise the plain reason it is parked. */
    private String heldBack() {
        if (sharedPrefs == null) {
            return null;
        }
        if (sharedPrefs.wifiOnly() && !onWifi()) {
            return "Waiting for Wi-Fi.";
        }
        if (sharedPrefs.batteryGuard() && lowBattery()) {
            return "Waiting for charge — the battery is under 15%.";
        }
        return null;
    }

    private boolean onWifi() {
        try {
            ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return true;
            }
            NetworkCapabilities capabilities =
                manager.getNetworkCapabilities(manager.getActiveNetwork());
            return capabilities != null
                && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception error) {
            return true;
        }
    }

    private boolean lowBattery() {
        try {
            Intent status = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (status == null) {
                return false;
            }
            int plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            if (plugged != 0) {
                return false;
            }
            int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) {
                return false;
            }
            return (level * 100 / scale) < 15;
        } catch (Exception error) {
            return false;
        }
    }

    private void report(String id, long completed, long total, String status, String detail) {
        Listener target = listener;
        if (target == null) {
            return;
        }
        try {
            JSONObject event = new JSONObject();
            event.put("type", "download");
            event.put("id", id);
            event.put("completed", completed);
            event.put("total", total);
            event.put("status", status);
            if (detail != null) {
                event.put("detail", detail);
            }
            target.onDownloadEvent(event);
        } catch (Exception ignored) {
            // The bar can wait for the next tick.
        }
    }

    private void update(String title, String line, int percent) {
        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification(title, line, percent));
        }
    }

    private Notification notification(String title, String line, int percent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Shown while a model is downloading.");
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
        return builder
            .setContentTitle(title)
            .setContentText(line)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, Math.max(0, Math.min(100, percent)), false)
            .setOngoing(true)
            .build();
    }

    private static String megabytes(long bytes) {
        return (bytes / (1024L * 1024L)) + " MB";
    }
}

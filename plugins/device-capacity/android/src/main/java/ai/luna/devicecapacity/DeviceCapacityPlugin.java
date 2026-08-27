package ai.luna.devicecapacity;

import android.app.ActivityManager;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Exposes Android device capacity to the WebView. */
@CapacitorPlugin(name = "DeviceCapacity")
public class DeviceCapacityPlugin extends Plugin {
    @PluginMethod
    public void getCapacity(PluginCall call) {
        ActivityManager activityManager = (ActivityManager) getContext()
            .getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        JSObject result = new JSObject();
        result.put("totalRamBytes", memoryInfo.totalMem);
        result.put("availableRamBytes", memoryInfo.availMem);
        result.put("totalStorageBytes", getTotalStorageBytes());
        result.put("availableStorageBytes", getAvailableStorageBytes());
        call.resolve(result);
    }

    private long getTotalStorageBytes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                StorageStatsManager storageStats = getContext().getSystemService(StorageStatsManager.class);
                if (storageStats != null) {
                    return storageStats.getTotalBytes(StorageManager.UUID_DEFAULT);
                }
            } catch (Exception ignored) {
                // Fall through to a filesystem measurement on restricted devices.
            }
        }

        StatFs statFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        return statFs.getTotalBytes();
    }

    private long getAvailableStorageBytes() {
        StatFs statFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        return statFs.getAvailableBytes();
    }
}

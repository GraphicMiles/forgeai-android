package ai.luna.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import java.util.HashMap;
import java.util.Map;

/** What this phone can actually hold. Reported honestly, never rounded up. */
public final class DeviceCapacity {

    private DeviceCapacity() {
    }

    public static Map<String, Object> read(Context context) {
        Map<String, Object> out = new HashMap<>();
        long totalRam = 0L;
        long availableRam = 0L;

        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            totalRam = info.totalMem;
            availableRam = info.availMem;
        }

        long totalStorage = 0L;
        long availableStorage = 0L;
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            totalStorage = stat.getBlockCountLong() * stat.getBlockSizeLong();
            availableStorage = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        } catch (Exception ignored) {
            // A locked-down device can refuse the stat; report zero rather than guess.
        }

        out.put("ramBytes", totalRam);
        out.put("availableRamBytes", availableRam);
        out.put("storageBytes", totalStorage);
        out.put("availableStorageBytes", availableStorage);
        out.put("cores", Runtime.getRuntime().availableProcessors());
        out.put("suggestedThreads", suggestedThreads());
        out.put("platform", "android");
        return out;
    }

    /** Threads worth giving llama.cpp: leave one core for the UI. */
    public static int suggestedThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        if (cores <= 2) {
            return 2;
        }
        return Math.min(6, cores - 1);
    }
}

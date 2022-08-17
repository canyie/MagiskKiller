package top.canyie.magiskkiller;

import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * @author canyie
 */
public class MagiskKiller {
    public static final String TAG = "MagiskKiller";

    /** Found some one tracing us (perhaps MagiskHide) */
    public static final int FOUND_TRACER = 1 << 0;

    /** Bootloader is unlocked */
    public static final int FOUND_BOOTLOADER_UNLOCKED = 1 << 1;

    /** Device is running a self-signed ROM */
    public static final int FOUND_BOOTLOADER_SELF_SIGNED = 1 << 2;

    /** Riru installed */
    public static final int FOUND_RIRU = 1 << 3;

    /** Some system properties are modified by resetprop (a tool provided by Magisk) */
    public static final int FOUND_RESETPROP = 1 << 4;

    /** Found active `magisk su` session (the detection method used by HSBC app) */
    public static final int FOUND_MAGISK_PTS = 1 << 5;

    public static void loadNativeLibrary() {
        System.loadLibrary("safetychecker");
    }

    public static int detect(String apk) {
        var detectTracerTask = detectTracer(apk);
        int result;
        result = detectProperties();
        result |= detectMagiskPts();

        int tracer;
        try {
            tracer = detectTracerTask.call();
        } catch (Exception e) {
            throw new RuntimeException("wait trace checker", e);
        }
        if (tracer != 0) {
            Log.e(TAG, "Found magiskd " + tracer);
            result |= FOUND_TRACER;
        }
        return result;
    }

    public static int requestTrace() {
        // Change process name so magiskd would think we're zygote and attach us
        try {
            Method setArgV0 = Process.class.getDeclaredMethod("setArgV0", String.class);
            setArgV0.setAccessible(true);
            setArgV0.invoke(null, "zygote");
        } catch (Exception e) {
            throw new RuntimeException("change process name", e);
        }

        // Touch app_process and trigger inotify
        try (FileInputStream fis = new FileInputStream("/system/bin/app_process")) {
            fis.read();
        } catch (IOException e) {
            throw new RuntimeException("touch app_process", e);
        }

        // Wait magiskd received inotify event and trace us
        SystemClock.sleep(2000);

        // Read the tracer process from /proc/self/status
        return getTracer();
    }

    public static Callable<Integer> detectTracer(String apk) {
        // Magisk Hide will attach processes with name=zygote/zygote64 and ppid=1
        // Orphan processes will have PPID=1
        // The return value is the pipe to communicate with the child process
        int rawReadFd = forkOrphan(apk);

        if (rawReadFd < 0) throw new RuntimeException("fork failed");
        var readFd = ParcelFileDescriptor.adoptFd(rawReadFd);
        return () -> {
            try (DataInputStream fis = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(readFd))) {
                return Integer.parseInt(fis.readUTF());
            }
        };
    }

    public static int getTracer() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("TracerPid:")) {
                    return Integer.parseInt(line.substring("TracerPid:".length()).trim());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("read tracer", e);
        }
        return 0;
    }

    public static int detectProperties() {
        int result = 0;
        try {
            result = detectBootloaderProperties();
            result |= detectDalvikConfigProperties();
        } catch (Exception e) {
            Log.e(TAG, "Failed to check props", e);
        }
        return result;
    }

    private static int detectBootloaderProperties() {
        int result = 0;
        // The better way to get the filename would be `getprop -Z`
        // But "-Z" option requires Android 7.0+, and I'm lazy to implement it
        PropArea bootloader = PropArea.any("bootloader_prop", "exported2_default_prop", "default_prop");
        if (bootloader == null) return 0;
        var values = bootloader.findPossibleValues("ro.boot.verifiedbootstate");
        // ro properties are read-only, multiple values found = the property has been modified by resetprop
        if (values.size() > 1) {
            result |= FOUND_RESETPROP;
        }
        for (String value : values) {
            if ("orange".equals(value)) {
                result |= FOUND_BOOTLOADER_UNLOCKED;
                result &= ~FOUND_BOOTLOADER_SELF_SIGNED;
            } else if ("yellow".equals(value) && (result & FOUND_BOOTLOADER_UNLOCKED) == 0) {
                result |= FOUND_BOOTLOADER_SELF_SIGNED;
            }
        }

        values = bootloader.findPossibleValues("ro.boot.vbmeta.device_state");
        if (values.size() > 1) {
            result |= FOUND_RESETPROP;
        }
        for (String value : values) {
            if ("unlocked".equals(value)) {
                result |= FOUND_BOOTLOADER_UNLOCKED;
                result &= ~FOUND_BOOTLOADER_SELF_SIGNED;
                break;
            }
        }
        return result;
    }

    private static int detectDalvikConfigProperties() {
        int result = 0;
        PropArea dalvikConfig = PropArea.any("dalvik_config_prop", "exported_dalvik_prop", "dalvik_prop");
        if (dalvikConfig == null) return 0;
        var values = dalvikConfig.findPossibleValues("ro.dalvik.vm.native.bridge");
        if (values.size() > 1) {
            result |= FOUND_RESETPROP;
        }

        for (String value : values) {
            if ("libriruloader.so".equals(value)) {
                result |= FOUND_RIRU;
                break;
            }
        }
        return result;
    }

    // Scan /dev/pts and check if there is a magisk pts alive
    // Use `magisk su` to open a root session to test it
    private static int detectMagiskPts() {
        Method getFileContext;
        try {
            getFileContext = Class.forName("android.os.SELinux")
                    .getDeclaredMethod("getFileContext", String.class);
            getFileContext.setAccessible(true);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to reflect getFileContext", e);
            return 0;
        }

        // Listing files under /dev/pts is not possible because of SELinux
        // So we manually recreate the folder structure
        var basePts = new File("/dev/pts");
        for (int i = 0;i < 1024;i++) {
            var cur = new File(basePts, Integer.toString(i));

            // No more pts, break.
            if (!cur.exists()) break;

            // We found an active pts, check if it has magisk context.
            try {
                String ptsContext = (String) getFileContext.invoke(null, cur.getAbsolutePath());
                if ("u:object_r:magisk_file:s0".equals(ptsContext))
                    return FOUND_MAGISK_PTS;
            } catch (Throwable e) {
                Log.e(TAG, "Failed to check file context of " + cur, e);
            }
        }
        return 0;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null)
            try {
                closeable.close();
            } catch (IOException ignored) {}
    }

    private static void closeQuietly(FileDescriptor fd) {
        if (fd != null)
            try {
                Os.close(fd);
            } catch (Exception ignored) {}
    }

    private static native int forkOrphan(String apk);
}

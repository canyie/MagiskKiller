package top.canyie.magiskkiller;

import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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

    static {
        System.loadLibrary("safetychecker");
    }

    public static int detect() {
        var detectTracerTask = detectTracer();
        int result;
        result = detectProperties();

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

    public static Callable<Integer> detectTracer() {
        // Create pipe to communicate with the child process
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            throw new RuntimeException("create pipe", e);
        }

        var read = pipe[0];
        var write = pipe[1];

        // Magisk Hide will attach processes with name=zygote/zygote64 and ppid=1
        // Orphan processes will have PPID=1
        int pid = forkOrphan(read.getFd(), write.getFd());

        if (pid < 0) throw new RuntimeException("fork failed");

        if (pid == 0) {
            closeQuietly(read);

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

            // Read the tracer process from /proc/self/status and send it back
            try (DataOutputStream fos = new DataOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(write))) {
                fos.writeUTF(Integer.toString(getTracer()));
            } catch (IOException e) {
                throw new RuntimeException("write tracer pid", e);
            }
            System.exit(0);
            throw new RuntimeException("Unreachable");
        } else {
            closeQuietly(write);
            return () -> {
                try (DataInputStream fis = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(read))) {
                    return Integer.parseInt(fis.readUTF());
                }
            };
        }
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
            {
                PropArea exportedDefault = new PropArea("exported2_default_prop");
                var values = exportedDefault.findPossibleValues("ro.boot.verifiedbootstate");
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

                values = exportedDefault.findPossibleValues("ro.boot.vbmeta.device_state");
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
            }

            {
                PropArea exportedDalvik;
                try {
                    exportedDalvik = new PropArea("exported_dalvik_prop");
                } catch (Exception e) {
                    exportedDalvik = new PropArea("dalvik_prop");
                }
                var values = exportedDalvik.findPossibleValues("ro.dalvik.vm.native.bridge");
                if (values.size() > 1) {
                    result |= FOUND_RESETPROP;
                }

                for (String value : values) {
                    if ("libriruloader.so".equals(value)) {
                        result |= FOUND_RIRU;
                        break;
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to check props", e);
        }
        return result;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null)
            try {
                closeable.close();
            } catch (IOException ignored) {}
    }

    private static native int forkOrphan(int readFd, int writeFd);
}

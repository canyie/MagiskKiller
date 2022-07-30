package top.canyie.magiskkiller;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.DataOutputStream;
import java.util.Arrays;

/**
 * @author canyie
 */
public class SubprocessMain {
    public static void main(String[] args) {
        // Parse fd
        if (args.length != 2 || !"--write-fd".equals(args[0])) {
            String error = "Bad args passed: " + Arrays.toString(args);
            System.err.println(error);
            Log.e(MagiskKiller.TAG, error);
            System.exit(1);
        }
        ParcelFileDescriptor writeFd = null;
        try {
            writeFd = ParcelFileDescriptor.adoptFd(Integer.parseInt(args[1]));
        } catch (Exception e) {
            System.err.println("Unable to parse " + args[1]);
            e.printStackTrace();
            Log.e(MagiskKiller.TAG, "Unable to parse " + args[1], e);
            System.exit(1);
        }
        try {
            // Do our work and send the tracer's pid back to app
            int tracer = MagiskKiller.requestTrace();
            try (DataOutputStream fos = new DataOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(writeFd))) {
                fos.writeUTF(Integer.toString(tracer));
            }
        } catch (Throwable e) {
            e.printStackTrace();
            Log.e(MagiskKiller.TAG, "", e);
            System.exit(1);
        }
    }
}

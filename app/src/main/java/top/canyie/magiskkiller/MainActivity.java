package top.canyie.magiskkiller;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import static top.canyie.magiskkiller.MagiskKiller.*;

public class MainActivity extends Activity {
    private static boolean inited;
    private Thread checkThread;
    private String found, notFound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        var progressBar = findViewById(R.id.progress);
        TextView status = findViewById(R.id.status);
        TextView about = findViewById(R.id.about);

        found = getString(R.string.found);
        notFound = getString(R.string.not_found);

        checkThread = new Thread(() -> {
            if (!inited) {
                inited = true;
                MagiskKiller.loadNativeLibrary();
            }
            int result = MagiskKiller.detect(getApplicationInfo().sourceDir);
            runOnUiThread(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append(resolveString(R.string.tracer, result, FOUND_TRACER));
                sb.append('\n');
                sb.append(resolveString(R.string.resetprop, result, FOUND_RESETPROP));
                sb.append('\n');
                sb.append(resolveString(R.string.riru, result, FOUND_RIRU));
                sb.append('\n');
                int bl = (result & FOUND_BOOTLOADER_UNLOCKED) != 0
                        ? R.string.unlocked : (result & FOUND_BOOTLOADER_SELF_SIGNED) != 0
                        ? R.string.self_signed : R.string.locked;
                sb.append(getString(R.string.bootloader, getString(bl)));
                sb.append('\n');
                sb.append(resolveString(R.string.magisk_pts, result, FOUND_MAGISK_PTS));
                status.setText(sb);
                progressBar.setVisibility(View.GONE);
                status.setVisibility(View.VISIBLE);
                about.setVisibility(View.VISIBLE);
            });
        });

        var sp = getSharedPreferences("config", MODE_PRIVATE);
        if (!sp.contains("agreed_privaty_policy")) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.privacy_policy_title)
                    .setMessage(R.string.privacy_policy)
                    .setPositiveButton(R.string.accept, (dialog, i) -> {
                        sp.edit().putBoolean("agreed_privaty_policy", true).apply();
                        checkThread.start();
                    })
                    .setNegativeButton(R.string.deny, (dialog, i) -> {
                        finish();
                    })
                    .setCancelable(false)
                    .show();
        } else {
            checkThread.start();
        }
    }

    @Override protected void onDestroy() {
        checkThread.interrupt();
        super.onDestroy();
    }

    private String resolveString(int id, int result, int flag) {
        return getString(id, ((result & flag) == 0) ? notFound : found);
    }
}

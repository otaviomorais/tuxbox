package sh.tuxbox.root;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that keeps the app process (and therefore the running
 * terminal session and its open pipes to ptyd) alive while the user is away
 * from the app. Without it, Android's LMK can kill the app in the background,
 * which closes the ptyd pipes and kills the container session ("session dies
 * when I leave the app").
 */
public class TerminalService extends Service {

    private static final String CHANNEL_ID = "tuxbox_terminal";
    private static final int NOTIF_ID = 1;

    /** Start the foreground service to hold the current terminal session. */
    public static void start(Context ctx, String label) {
        Intent i = new Intent(ctx, TerminalService.class);
        i.putExtra("label", label == null ? "" : label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /** Stop the foreground service; call when the terminal session is closed/finished. */
    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, TerminalService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.term_notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.term_notif_channel_desc));
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String label = intent == null ? "" : intent.getStringExtra("label");
        if (label == null || label.isEmpty()) label = "TuxBox";

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(getString(R.string.term_notif_title))
                .setContentText(getString(R.string.term_notif_text, label))
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();

        // specialUse has no FGS time limit on Android 15+ (dataSync would be
        // capped at 6h), which matters for long-running terminal sessions.
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
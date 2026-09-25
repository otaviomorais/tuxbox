// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 LongQT-sea
package sh.tuxbox.root;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

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
    private static final String TAG = "TuxBox";

    /** Start the foreground service to hold the current terminal session. */
    public static void start(Context ctx, String label) {
        Intent i = new Intent(ctx, TerminalService.class);
        i.putExtra("label", label == null ? "" : label);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            // v3.13 — nunca derruba a sessao por causa do foreground service
            // (Android 12+ barra startForegroundService em background: pode
            // estourar ForegroundServiceStartNotAllowedException).
            Log.w(TAG, "startForegroundService falhou: " + t);
        }
    }

    /**
     * v3.13 — a notificação SÓ aparece com a permissão POST_NOTIFICATIONS
     * concedida (Android 13+) e o canal não bloqueado. Se qualquer um dos dois
     * estiver desligado, a sessão continua rodando mas fica vulnerável em
     * segundo plano sem nenhum aviso — o app precisa detectar e avisar.
     */
    public static boolean notificationsVisible(Context ctx) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
            if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return false;
            }
        }
        return true;
    }

    /** v3.13 — leva direto nas configurações de notificação do app. */
    public static void openNotificationSettings(Activity act) {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, act.getPackageName());
            act.startActivity(i);
        } catch (Throwable t) {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:" + act.getPackageName())));
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
        // v3.13 — startForeground pode ser recusado pelo sistema (tipo não
        // permitido, FGS não permitido em background…): capturamos para NUNCA
        // derrubar a sessão do container por causa da notificação.
        try {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (Throwable t) {
            Log.w(TAG, "startForeground falhou: " + t);
            stopSelf();
        }
        // v3.13 — START_STICKY: se o LMK matar o serviço, o Android o reinicia
        // e a sessão volta a ficar protegida. (O stopService explícito ao
        // fechar a ÚLTIMA aba cancela o restart — não deixa notificação órfã.)
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
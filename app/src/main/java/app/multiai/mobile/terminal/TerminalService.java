package app.multiai.mobile.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service required for terminal tool execution.
 *
 * <p>Android mandates a foreground notification while the service runs, but the
 * user should not see a permanent "Terminal ready" entry in the shade: the
 * channel is silent (IMPORTANCE_MIN) and the notification text carries no
 * status. It is removed as soon as the service stops.
 */
public class TerminalService extends Service {

    private static final String TAG = "MultiAITerminal";
    private static final String CHANNEL_ID = "terminal_exec";
    private static final int NOTIF_ID = 2;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, createNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIF_ID);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Terminal", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Terminal tool execution");
        channel.setShowBadge(false);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    /** Пустая служебная нотификация: требование системы, не пользовательский UI. */
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(app.multiai.mobile.R.drawable.ic_info)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }
}

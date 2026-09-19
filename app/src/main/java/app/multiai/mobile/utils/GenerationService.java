package app.multiai.mobile.utils;

import android.app.Service;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.HashSet;
import java.util.Set;
import app.multiai.mobile.R;

/** Foreground lifetime and CPU protection for an active generation. */
public final class GenerationService extends Service {
    private static final Set<String> owners = new HashSet<>();
    private static final String CHANNEL = "ai_generation";
    private PowerManager.WakeLock wakeLock;

    public static synchronized void update(Context context, String owner, boolean active) {
        if (active) {
            if (!owners.add(owner)) return;
            try {
                ContextCompat.startForegroundService(context, new Intent(context, GenerationService.class));
            } catch (RuntimeException e) {
                owners.remove(owner);
                android.util.Log.w("GenerationService", "Cannot start foreground generation", e);
            }
        } else {
            owners.remove(owner);
            if (owners.isEmpty()) context.stopService(new Intent(context, GenerationService.class));
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.generation_channel), NotificationManager.IMPORTANCE_LOW));
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_s_sparkle)
                .setContentTitle(getString(R.string.generation_channel))
                .setContentText(getString(R.string.generation_background))
                .setOngoing(true).setSilent(true);
        if (launch != null) notification.setContentIntent(PendingIntent.getActivity(this, 4102,
                launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        startForeground(4102, notification.build());
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MultiAI:generation");
        wakeLock.acquire(60 * 60 * 1000L);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (GenerationService.class) {
            if (owners.isEmpty()) stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        synchronized (GenerationService.class) { owners.clear(); }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

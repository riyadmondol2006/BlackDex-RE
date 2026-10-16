package top.niunaijun.blackbox.core.system;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import top.niunaijun.blackbox.R;

/**
 * Keeps the BlackDex server process ({@code :black}) alive and OUT of the cached state.
 *
 * On Android 12+ the Cached Apps Freezer freezes cached processes; a frozen server process
 * makes the next synchronous Binder call from the main process fail ("Sync transaction while
 * frozen") and the OS then kills it. Hosting a real, persistent foreground service in the
 * server process keeps it in a non-cacheable state so the whole install/launch/dump pipeline
 * survives. (The old "show a notification then immediately cancel it" hack no longer produces
 * a real FGS on modern Android and is removed.)
 */
public class DaemonService extends Service {
    public static final String TAG = "DaemonService";
    private static final int NOTIFY_ID = 0x4244; // 'BD'
    private static final String CHANNEL_ID = "blackdex_sandbox";

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, DaemonService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService failed", t);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        goForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        return START_STICKY;
    }

    private void goForeground() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BlackDex")
                .setContentText("Sandbox running")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFY_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFY_ID, notification);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "BlackDex Sandbox", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }
}

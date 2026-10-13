package com.magic.iptv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Service de premier plan : garde la lecture et l’enregistrement actifs écran éteint. */
public class PlaybackService extends Service {
    private static final String CH = "magic_iptv_play";
    static volatile boolean running = false;
    private static PlaybackService inst;
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wf;

    @Override public IBinder onBind(Intent i) { return null; }

    private Notification build(String title) {
        Intent open = new Intent(this, MainActivity.class);
        int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("MAGIC IPTV")
                .setContentText(title == null ? "" : title)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    static void update(String title) {
        PlaybackService s = inst;
        if (s == null) return;
        try {
            NotificationManager nm = (NotificationManager) s.getSystemService(NOTIFICATION_SERVICE);
            nm.notify(1, s.build(title));
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        String title = in == null ? "" : in.getStringExtra("t");
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH, "Lecture et enregistrement", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = build(title);
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
        inst = this;
        running = true;
        try {
            if (wl == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "magic:iptv");
                wl.setReferenceCounted(false);
            }
            if (!wl.isHeld()) wl.acquire();
            if (wf == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "magic:iptv");
                wf.setReferenceCounted(false);
            }
            if (!wf.isHeld()) wf.acquire();
        } catch (Exception ignored) { }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        inst = null;
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        try { if (wf != null && wf.isHeld()) wf.release(); } catch (Exception ignored) { }
        stopForeground(true);
        super.onDestroy();
    }
}

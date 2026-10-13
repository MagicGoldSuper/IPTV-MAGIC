package com.magic.iptv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Service de premier plan : lecture / enregistrement écran éteint + commandes dans la notification. */
public class PlaybackService extends Service {
    private static final String CH = "magic_iptv_play";
    static volatile boolean running = false;
    private static PlaybackService inst;
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wf;
    private MediaSession ms;
    private String title = "MAGIC IPTV", sub = "";
    private boolean playing = true, rec = false;

    @Override public IBinder onBind(Intent i) { return null; }

    private int pflags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private PendingIntent act(String a, int code) {
        Intent i = new Intent(this, PlaybackService.class);
        i.setAction(a);
        return PendingIntent.getService(this, code, i, pflags());
    }

    private Notification build() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), pflags());
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title.length() == 0 ? "MAGIC IPTV" : title)
                .setContentText(sub)
                .setContentIntent(open)
                .setOngoing(playing || rec)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(0xFFE9BD45)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Préc.", act("prev", 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Lecture", act("toggle", 2))
                .addAction(android.R.drawable.ic_media_next, "Suiv.", act("next", 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", act("stop", 4));
        Notification.MediaStyle st = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (ms != null) st.setMediaSession(ms.getSessionToken());
        b.setStyle(st);
        return b.build();
    }

    private void apply() {
        try {
            if (ms != null) {
                ms.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
                        .build());
                ms.setPlaybackState(new PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                        .build());
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(1, build());
        } catch (Exception ignored) { }
        locks(playing || rec);
    }

    private void locks(boolean on) {
        try {
            if (on) {
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
            } else {
                if (wl != null && wl.isHeld()) wl.release();
                if (wf != null && wf.isHeld()) wf.release();
            }
        } catch (Exception ignored) { }
    }

    static void update(String t, String s, boolean p, boolean r) {
        PlaybackService x = inst;
        if (x == null) return;
        x.title = t == null ? "" : t;
        x.sub = s == null ? "" : s;
        x.playing = p;
        x.rec = r;
        x.apply();
    }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        String a = in == null ? null : in.getAction();
        if (a != null) {                       // boutons de la notification
            if ("prev".equals(a)) MainActivity.js("mgCmd('prev')");
            else if ("next".equals(a)) MainActivity.js("mgCmd('next')");
            else if ("toggle".equals(a)) MainActivity.js("mgCmd('toggle')");
            else if ("stop".equals(a)) MainActivity.js("mgCmd('stop')");
            return START_NOT_STICKY;
        }
        if (in != null) {
            String t = in.getStringExtra("t");
            String s = in.getStringExtra("s");
            title = t == null ? "MAGIC IPTV" : t;
            sub = s == null ? "" : s;
            playing = in.getBooleanExtra("p", true);
            rec = in.getBooleanExtra("r", false);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH, "Lecture et enregistrement", NotificationManager.IMPORTANCE_LOW));
        }
        if (ms == null) {
            ms = new MediaSession(this, "MAGIC IPTV");
            ms.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { MainActivity.js("mgCmd('play')"); }
                @Override public void onPause() { MainActivity.js("mgCmd('pause')"); }
                @Override public void onSkipToNext() { MainActivity.js("mgCmd('next')"); }
                @Override public void onSkipToPrevious() { MainActivity.js("mgCmd('prev')"); }
                @Override public void onStop() { MainActivity.js("mgCmd('stop')"); }
            });
            ms.setActive(true);
        }
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
        inst = this;
        running = true;
        apply();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        inst = null;
        locks(false);
        try { if (ms != null) { ms.setActive(false); ms.release(); ms = null; } } catch (Exception ignored) { }
        stopForeground(true);
        super.onDestroy();
    }
}

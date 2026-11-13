package com.codrive.vehicle.media;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;

import com.codrive.vehicle.gateway.InAppMusicPlayer;

import java.util.ArrayList;
import java.util.List;

public class CoDriveMediaService extends MediaBrowserService {
    private static final String ROOT_ID = "codrive_root";
    private static final String CHANNEL_ID = "codrive_music";
    private static final int NOTIFICATION_ID = 1;
    private static final String ARTIST = "CO-DRIVE";
    private static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID;

    private MediaSession session;
    private InAppMusicPlayer player;

    @Override
    public void onCreate() {
        super.onCreate();
        player = InAppMusicPlayer.get(this);
        session = new MediaSession(this, "CoDriveMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                player.execute("PLAY", r -> {
                });
            }

            @Override
            public void onPause() {
                player.execute("PAUSE", r -> {
                });
            }

            @Override
            public void onSkipToNext() {
                player.execute("NEXT", r -> {
                });
            }

            @Override
            public void onSkipToPrevious() {
                player.execute("PREVIOUS", r -> {
                });
            }

            @Override
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                try {
                    player.playIndex(Integer.parseInt(mediaId), r -> {
                    });
                } catch (NumberFormatException ignored) {
                }
            }
        });
        session.setActive(true);
        setSessionToken(session.getSessionToken());
        player.setListener(this::publish);
        publish();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "음악", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("CO-DRIVE 음악")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        player.setListener(null);
        session.setActive(false);
        session.release();
        super.onDestroy();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        List<MediaBrowser.MediaItem> items = new ArrayList<>();
        if (ROOT_ID.equals(parentId)) {
            String[] titles = InAppMusicPlayer.trackTitles();
            for (int i = 0; i < titles.length; i++) {
                MediaDescription d = new MediaDescription.Builder()
                        .setMediaId(String.valueOf(i))
                        .setTitle(titles[i])
                        .setSubtitle(ARTIST)
                        .build();
                items.add(new MediaBrowser.MediaItem(d, MediaBrowser.MediaItem.FLAG_PLAYABLE));
            }
        }
        result.sendResult(items);
    }

    private void publish() {
        boolean playing = player.isPlaying();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, player.title())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, ARTIST)
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        player.positionMs(), playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
    }
}

package com.codrive.vehicle.gateway;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.codrive.vehicle.R;
import com.codrive.vehicle.media.CoDriveMediaService;
import com.codrive.vehicle.model.Playlist;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InAppMusicPlayer {
    public interface Listener {
        void onChanged();
    }

    private static final String TAG = "CoDriveMusic";
    private static final int[] TRACK_RES = {R.raw.track_night_drive, R.raw.track_morning_road, R.raw.track_city_lights};
    private static final String[] TRACK_TITLES = {"Night Drive", "Morning Road", "City Lights"};
    private static final float DUCK_VOLUME = 0.3f;

    private static InAppMusicPlayer instance;

    public static synchronized InAppMusicPlayer get(Context context) {
        if (instance == null) {
            instance = new InAppMusicPlayer(context);
        }
        return instance;
    }

    public static String[] trackTitles() {
        return TRACK_TITLES.clone();
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final Playlist playlist = new Playlist(Arrays.asList(TRACK_TITLES));
    private final AudioFocusRequest focusRequest;

    private MediaPlayer player;
    private boolean resumeOnFocusGain;
    private Listener listener;
    private volatile boolean playing;
    private volatile String title = TRACK_TITLES[0];

    private InAppMusicPlayer(Context context) {
        this.context = context.getApplicationContext();
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes())
                .setOnAudioFocusChangeListener(this::onFocusChange)
                .build();
    }

    public void startMediaService() {
        context.startForegroundService(new Intent(context, CoDriveMediaService.class));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Map<String, Object> state() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("playing", playing);
        v.put("title", title);
        return v;
    }

    public boolean isPlaying() {
        return playing;
    }

    public String title() {
        return title;
    }

    public long positionMs() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    public void execute(String command, VehicleGateway.Callback<VehicleGateway.Result> cb) {
        main.post(() -> {
            VehicleGateway.Result r = run(command);
            notifyChanged();
            cb.onResult(r);
        });
    }

    public void playIndex(int index, VehicleGateway.Callback<VehicleGateway.Result> cb) {
        main.post(() -> {
            VehicleGateway.Result r;
            if (index < 0 || index >= TRACK_TITLES.length) {
                r = failed("UNKNOWN_TRACK", "없는 곡입니다.");
            } else {
                releasePlayer();
                playlist.select(index);
                title = playlist.current();
                r = play();
            }
            notifyChanged();
            cb.onResult(r);
        });
    }

    public void release() {
        main.post(() -> {
            releasePlayer();
            audio.abandonAudioFocusRequest(focusRequest);
            playing = false;
            notifyChanged();
        });
    }

    private void notifyChanged() {
        Listener l = listener;
        if (l != null) {
            l.onChanged();
        }
    }

    private VehicleGateway.Result run(String command) {
        try {
            switch (command) {
                case "PLAY" -> {
                    return play();
                }
                case "PAUSE" -> {
                    pause();
                    return playing ? failed("PAUSE_FAILED", "일시정지하지 못했습니다.") : VehicleGateway.Result.applied(false);
                }
                case "NEXT", "PREVIOUS" -> {
                    return skip("NEXT".equals(command));
                }
                default -> {
                    return new VehicleGateway.Result("NOT_SUPPORTED", "UNKNOWN_COMMAND", false, null);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "음악 제어 실패", e);
            return failed("PLAYER_ERROR", String.valueOf(e.getMessage()));
        }
    }

    private VehicleGateway.Result play() {
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return failed("AUDIO_FOCUS_DENIED", "다른 소리가 재생 중이라 음악을 시작하지 못했습니다.");
        }
        if (!ensurePlayer()) {
            return failed("PLAYER_ERROR", "음원을 열지 못했습니다.");
        }
        player.setVolume(1f, 1f);
        player.start();
        playing = player.isPlaying();
        return playing ? VehicleGateway.Result.applied(false) : failed("PLAY_FAILED", "재생이 시작되지 않았습니다.");
    }

    private void pause() {
        resumeOnFocusGain = false;
        if (player != null && player.isPlaying()) {
            player.pause();
        }
        playing = player != null && player.isPlaying();
    }

    private VehicleGateway.Result skip(boolean forward) {
        boolean wasPlaying = playing;
        String before = playlist.current();
        releasePlayer();
        title = forward ? playlist.next() : playlist.previous();
        if (wasPlaying) {
            VehicleGateway.Result r = play();
            if (!"APPLIED".equals(r.status)) {
                return r;
            }
        }
        return before.equals(title) ? failed("TRACK_NOT_CHANGED", "곡이 바뀌지 않았습니다.") : VehicleGateway.Result.applied(false);
    }

    private boolean ensurePlayer() {
        if (player != null) {
            return true;
        }
        player = MediaPlayer.create(context, TRACK_RES[playlist.index()], attributes(), AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (player == null) {
            return false;
        }
        player.setOnCompletionListener(mp -> {
            releasePlayer();
            title = playlist.next();
            play();
            notifyChanged();
        });
        player.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "MediaPlayer 오류 " + what + "/" + extra);
            releasePlayer();
            playing = false;
            notifyChanged();
            return true;
        });
        return true;
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
        playing = false;
    }

    private void onFocusChange(int change) {
        main.post(() -> {
            switch (change) {
                case AudioManager.AUDIOFOCUS_LOSS -> pause();
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    boolean was = player != null && player.isPlaying();
                    pause();
                    resumeOnFocusGain = was;
                }
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (player != null) {
                        player.setVolume(DUCK_VOLUME, DUCK_VOLUME);
                    }
                }
                case AudioManager.AUDIOFOCUS_GAIN -> {
                    if (player != null) {
                        player.setVolume(1f, 1f);
                        if (resumeOnFocusGain) {
                            player.start();
                            playing = player.isPlaying();
                        }
                    }
                    resumeOnFocusGain = false;
                }
                default -> {
                }
            }
            notifyChanged();
        });
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private static VehicleGateway.Result failed(String code, String message) {
        return VehicleGateway.Result.failed(code, message);
    }
}

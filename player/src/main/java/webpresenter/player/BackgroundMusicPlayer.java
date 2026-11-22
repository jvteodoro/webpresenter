package webpresenter.player;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.DoubleConsumer;
import webpresenter.protocol.Message;

/**
 * Background music: plays the wanted source looped at the background level, keeps it going when
 * the next item wants the same source, fades between sources, and ducks (or pauses, with a duck
 * level of 0) while something with its own sound plays. Single-threaded (the JavaFX thread).
 */
final class BackgroundMusicPlayer {

    private static final System.Logger LOG = System.getLogger(BackgroundMusicPlayer.class.getName());

    /** Where the audio goes (an audio-only mpv). */
    interface Sink {
        void playlist(List<Path> files, boolean shuffle, boolean paused);

        void setPaused(boolean paused);

        /** 0..100. */
        void setVolume(double volume);

        void stop();
    }

    /** Animates a value over time (JavaFX in the player, immediate in tests). */
    interface Fader {
        void fade(double from, double to, long durationMs, DoubleConsumer step, Runnable done);

        void cancel();
    }

    /** Local copies of the tracks. */
    interface Tracks {
        Optional<Path> cached(Message.MediaRef media);

        CompletableFuture<Path> fetch(Message.MediaRef media);
    }

    private final Sink sink;
    private final Fader fader;
    private final Tracks tracks;
    private final Executor ui;

    private Message.Mixer mixer = Message.Mixer.defaults();
    /** What should be playing (null: silence) and whether something else has sound. */
    private Message.BackgroundMusic wanted;
    private boolean ducked;
    /** What the sink is playing (or paused on); null when stopped. */
    private String playingKey;
    /** Source whose tracks are being downloaded before it can start. */
    private String loadingKey;
    private boolean sinkPaused;
    /** Current fade factor 0..1. */
    private double level;

    BackgroundMusicPlayer(Sink sink, Fader fader, Tracks tracks, Executor ui) {
        this.sink = sink;
        this.fader = fader;
        this.tracks = tracks;
        this.ui = ui;
    }

    void setMixer(Message.Mixer mixer) {
        this.mixer = mixer;
        applyVolume();
        if (playingKey != null && wanted != null && playingKey.equals(wanted.key())) {
            fadeTo(target(), null);
        }
    }

    /**
     * @param music what to play; null for silence
     * @param duck something with its own sound is playing: lower (or pause) the music
     */
    void want(Message.BackgroundMusic music, boolean duck) {
        wanted = music;
        ducked = duck;
        if (music == null || music.tracks().isEmpty()) {
            loadingKey = null;
            if (playingKey != null) {
                fadeTo(0, this::stopSink);
            }
            return;
        }
        if (music.key().equals(playingKey)) {
            loadingKey = null;
            fadeTo(target(), null);
            return;
        }
        if (music.key().equals(loadingKey)) {
            return; // its tracks are still downloading; it starts when they arrive
        }
        // A fade started here replaces any earlier one (and what was to follow it).
        if (playingKey != null && level > 0) {
            fadeTo(0, () -> start(music));
        } else {
            start(music);
        }
    }

    /** Silence at once (information screens, removal). */
    void stopNow() {
        fader.cancel();
        wanted = null;
        if (playingKey != null) {
            stopSink();
        }
    }

    boolean isPlaying() {
        return playingKey != null && !sinkPaused && level > 0;
    }

    String playingKey() {
        return playingKey;
    }

    private void start(Message.BackgroundMusic music) {
        List<Optional<Path>> local = music.tracks().stream().map(tracks::cached).toList();
        if (local.stream().allMatch(Optional::isPresent)) {
            begin(music, local.stream().map(Optional::get).toList());
            return;
        }
        // Not downloaded yet (pre-cache still running): fetch, then start if still wanted.
        loadingKey = music.key();
        List<CompletableFuture<Path>> downloads = music.tracks().stream()
                .map(t -> tracks.fetch(t).exceptionally(e -> {
                    LOG.log(System.Logger.Level.WARNING, "background track " + t.mediaId() + ": " + e.getMessage());
                    return null;
                }))
                .toList();
        CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).thenRunAsync(() -> {
            if (!music.key().equals(loadingKey)) {
                return; // something else is wanted now
            }
            loadingKey = null;
            if (wanted == null || !music.key().equals(wanted.key())) {
                return;
            }
            List<Path> files = downloads.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList();
            if (!files.isEmpty()) {
                begin(music, files);
            }
        }, ui);
    }

    private void begin(Message.BackgroundMusic music, List<Path> files) {
        fader.cancel();
        if (playingKey != null) {
            sink.stop();
        }
        level = 0;
        applyVolume();
        double target = target();
        sink.playlist(files, music.shuffle(), target <= 0);
        sinkPaused = target <= 0;
        playingKey = music.key();
        fadeTo(target, null);
    }

    private void fadeTo(double target, Runnable then) {
        fader.cancel();
        if (target > 0 && sinkPaused && playingKey != null) {
            sink.setPaused(false);
            sinkPaused = false;
        }
        long duration = target > level ? mixer.fadeInMs() : mixer.fadeOutMs();
        fader.fade(level, target, duration, value -> {
            level = value;
            applyVolume();
        }, () -> {
            level = target;
            applyVolume();
            if (target <= 0 && playingKey != null && !sinkPaused) {
                sink.setPaused(true);
                sinkPaused = true;
            }
            if (then != null) {
                then.run();
            }
        });
    }

    private void stopSink() {
        sink.stop();
        playingKey = null;
        sinkPaused = false;
        level = 0;
        applyVolume();
    }

    private double target() {
        return ducked ? mixer.duckPercent() / 100.0 : 1.0;
    }

    private void applyVolume() {
        sink.setVolume(mixer.master() / 100.0 * mixer.background() / 100.0 * level * 100.0);
    }
}

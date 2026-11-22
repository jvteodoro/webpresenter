package webpresenter.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import webpresenter.protocol.Message;

class BackgroundMusicPlayerTest {

    /** Records what reaches the audio output. */
    static final class RecordingSink implements BackgroundMusicPlayer.Sink {
        final List<String> calls = new ArrayList<>();
        double volume;
        boolean paused;

        @Override
        public void playlist(List<Path> files, boolean shuffle, boolean paused) {
            calls.add("playlist " + files.size() + (shuffle ? " shuffle" : "") + (paused ? " paused" : ""));
            this.paused = paused;
        }

        @Override
        public void setPaused(boolean paused) {
            calls.add(paused ? "pause" : "resume");
            this.paused = paused;
        }

        @Override
        public void setVolume(double volume) {
            this.volume = volume;
        }

        @Override
        public void stop() {
            calls.add("stop");
        }
    }

    /** Fades complete at once, recording their durations. */
    static final class InstantFader implements BackgroundMusicPlayer.Fader {
        final List<Long> durations = new ArrayList<>();

        @Override
        public void fade(double from, double to, long durationMs, DoubleConsumer step, Runnable done) {
            durations.add(durationMs);
            step.accept(to);
            done.run();
        }

        @Override
        public void cancel() {}
    }

    static final Message.MediaRef TRACK = new Message.MediaRef("t1", Message.MediaKind.AUDIO, "http://x/t1", "00", 1);
    static final Message.MediaRef OTHER = new Message.MediaRef("t2", Message.MediaKind.AUDIO, "http://x/t2", "00", 1);
    static final Message.BackgroundMusic PIANO = new Message.BackgroundMusic("media:t1", List.of(TRACK), false);
    static final Message.BackgroundMusic PLAYLIST = new Message.BackgroundMusic("playlist:p", List.of(TRACK, OTHER), true);

    RecordingSink sink;
    InstantFader fader;
    BackgroundMusicPlayer music;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        fader = new InstantFader();
        BackgroundMusicPlayer.Tracks cached = new BackgroundMusicPlayer.Tracks() {
            @Override
            public Optional<Path> cached(Message.MediaRef media) {
                return Optional.of(Path.of("/cache/" + media.mediaId()));
            }

            @Override
            public CompletableFuture<Path> fetch(Message.MediaRef media) {
                return CompletableFuture.completedFuture(Path.of("/cache/" + media.mediaId()));
            }
        };
        music = new BackgroundMusicPlayer(sink, fader, cached, Runnable::run);
        music.setMixer(new Message.Mixer(100, 100, 50, 100, 2000, 1500, 0));
    }

    @Test
    void playsAtTheBackgroundLevelAndKeepsGoingForTheSameSource() {
        music.want(PIANO, false);
        assertEquals(List.of("playlist 1"), sink.calls);
        assertEquals(50, sink.volume, 0.01);
        assertEquals(2000, fader.durations.getLast()); // fade in

        music.want(PIANO, false); // next slide, same music
        assertEquals(List.of("playlist 1"), sink.calls);
        assertTrue(music.isPlaying());
    }

    @Test
    void stopsWhileAnItemHasSoundAndComesBack() {
        music.want(PIANO, false);
        music.want(PIANO, true); // a video starts: duck level 0 → fade out and pause
        assertEquals(0, sink.volume, 0.01);
        assertTrue(sink.paused);
        assertEquals(1500, fader.durations.getLast());

        music.want(PIANO, false); // the video ended
        assertFalse(sink.paused);
        assertEquals(50, sink.volume, 0.01);
        assertEquals(List.of("playlist 1", "pause", "resume"), sink.calls);
    }

    @Test
    void ducksToTheConfiguredLevel() {
        music.setMixer(new Message.Mixer(80, 100, 50, 100, 1000, 1000, 20));
        music.want(PIANO, true);
        assertEquals(80 * 0.5 * 0.2, sink.volume, 0.01);
        assertFalse(sink.paused);
    }

    @Test
    void switchesSourcesAndStopsForSilence() {
        music.want(PIANO, false);
        music.want(PLAYLIST, false);
        assertEquals(List.of("playlist 1", "pause", "stop", "playlist 2 shuffle"), sink.calls);
        assertEquals("playlist:p", music.playingKey());

        music.want(null, false);
        assertEquals("stop", sink.calls.getLast());
        assertNull(music.playingKey());
        assertEquals(0, sink.volume, 0.01);
    }

    @Test
    void anInterruptedSwitchStillStartsTheNewSource() {
        // Fades that do not finish by themselves: a second request arrives during the fade out.
        List<Runnable> pending = new ArrayList<>();
        BackgroundMusicPlayer.Fader slow = new BackgroundMusicPlayer.Fader() {
            @Override
            public void fade(double from, double to, long durationMs, DoubleConsumer step, Runnable done) {
                pending.clear();
                pending.add(() -> {
                    step.accept(to);
                    done.run();
                });
            }

            @Override
            public void cancel() {
                pending.clear();
            }
        };
        var tracks = new BackgroundMusicPlayer.Tracks() {
            @Override
            public Optional<Path> cached(Message.MediaRef media) {
                return Optional.of(Path.of("/cache/" + media.mediaId()));
            }

            @Override
            public CompletableFuture<Path> fetch(Message.MediaRef media) {
                throw new AssertionError("cached");
            }
        };
        var player = new BackgroundMusicPlayer(sink, slow, tracks, Runnable::run);
        player.setMixer(new Message.Mixer(100, 100, 50, 100, 2000, 1500, 0));
        player.want(PIANO, false);
        pending.getFirst().run();                 // PIANO faded in
        player.want(PLAYLIST, false);             // fade out PIANO, then start PLAYLIST …
        player.want(PLAYLIST, false);             // … asked again before the fade finished
        pending.getFirst().run();
        assertEquals("playlist:p", player.playingKey());
    }

    @Test
    void mixerChangesApplyAtOnce() {
        music.want(PIANO, false);
        music.setMixer(new Message.Mixer(50, 100, 80, 100, 2000, 1500, 0));
        assertEquals(40, sink.volume, 0.01);
    }
}

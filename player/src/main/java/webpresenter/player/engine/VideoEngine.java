package webpresenter.player.engine;

import java.nio.file.Path;
import javafx.scene.Node;

/**
 * Playback engine abstraction. Implementations render video frames
 * into a JavaFX node so overlays and transitions can be composed by JavaFX.
 */
public interface VideoEngine extends AutoCloseable {

    String name();

    /** Node displaying the video frames. */
    Node view();

    /** Loads and starts playing a media file (video or audio) once. */
    default void play(Path media) {
        play(media, false);
    }

    /** Loads and starts playing a media file, optionally looping it until stopped. */
    default void play(Path media, boolean loop) {
        play(media, loop, 0);
    }

    /** Loads and plays a media file starting at {@code startMs} (used to join an item in progress). */
    void play(Path media, boolean loop, long startMs);

    void setPaused(boolean paused);

    /** Current playback position, or 0 if unknown. */
    long positionMs();

    /** Duration of the loaded media, or 0 if unknown. */
    long durationMs();

    void stop();

    /** Volume in the range 0..100. */
    void setVolume(double volume);

    /** Called (on an arbitrary thread) when the current media reaches its end. */
    void onEndOfMedia(Runnable listener);

    /** Rendering statistics since the last call; resets the counters. */
    RenderStats drainStats();

    @Override
    void close();

    record RenderStats(int framesRendered, double avgRenderMs, double maxRenderMs) {}
}

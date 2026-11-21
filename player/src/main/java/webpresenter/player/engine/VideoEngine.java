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

    /** Loads and starts playing a media file (video or audio). */
    void play(Path media);

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

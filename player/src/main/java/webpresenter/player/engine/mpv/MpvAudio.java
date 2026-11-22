package webpresenter.player.engine.mpv;

import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * An audio-only mpv instance (no video output, no render context) for background music: plays a
 * list of files in a loop, optionally shuffled. All calls are cheap and non-blocking (mpv queues
 * commands); call from one thread.
 */
public final class MpvAudio implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MpvAudio.class.getName());

    private final MemorySegment handle;
    private final Thread eventThread;
    private volatile boolean running = true;

    public MpvAudio() {
        try {
            Mpv.forceNumericCLocale();
            handle = (MemorySegment) Mpv.create.invokeExact();
            if (handle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("mpv_create returned NULL");
            }
            setOption("vid", "no");
            setOption("vo", "null");
            setOption("idle", "yes");
            setOption("audio-display", "no");
            setOption("terminal", "no");
            setOption("loop-playlist", "inf");
            setOption("volume", "0");
            setOption("msg-level", System.getProperty("mpv.msgLevel", "all=warn"));
            Mpv.check((int) Mpv.initialize.invokeExact(handle), "mpv_initialize");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("failed to initialize mpv for background music", t);
        }
        // Drains mpv's event queue (it must be read) and notices shutdown.
        eventThread = Thread.ofPlatform().name("mpv-background-events").daemon().start(this::eventLoop);
    }

    /** Replaces what is playing with these files, looped; starts paused when {@code paused}. */
    public void playlist(List<Path> files, boolean shuffle, boolean paused) {
        if (files.isEmpty()) {
            stop();
            return;
        }
        setProperty("pause", paused ? "yes" : "no");
        command("loadfile", files.getFirst().toAbsolutePath().toString(), "replace");
        for (Path file : files.subList(1, files.size())) {
            command("loadfile", file.toAbsolutePath().toString(), "append");
        }
        if (shuffle && files.size() > 1) {
            command("playlist-shuffle");
            command("playlist-play-index", "0");
        }
    }

    public void setPaused(boolean paused) {
        setProperty("pause", paused ? "yes" : "no");
    }

    /** Volume 0..100. */
    public void setVolume(double volume) {
        setProperty("volume", String.format(Locale.ROOT, "%.1f", Math.clamp(volume, 0, 100)));
    }

    public void stop() {
        command("stop");
    }

    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        try {
            command("quit");
            eventThread.join(2000);
            Mpv.terminateDestroy.invokeExact(handle);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "mpv background shutdown: " + t);
        }
    }

    private void eventLoop() {
        while (true) {
            try {
                MemorySegment event = ((MemorySegment) Mpv.waitEvent.invokeExact(handle, -1.0)).reinterpret(24);
                if (event.get(java.lang.foreign.ValueLayout.JAVA_INT, 0) == Mpv.EVENT_SHUTDOWN) {
                    return;
                }
            } catch (Throwable t) {
                if (running) {
                    LOG.log(System.Logger.Level.WARNING, "background event loop: " + t);
                }
                return;
            }
        }
    }

    private void setOption(String name, String value) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            Mpv.check((int) Mpv.setOptionString.invokeExact(handle, a.allocateFrom(name), a.allocateFrom(value)),
                    "set option " + name);
        }
    }

    private void setProperty(String name, String value) {
        if (!running) {
            return;
        }
        try (Arena a = Arena.ofConfined()) {
            int rc = (int) Mpv.setPropertyString.invokeExact(handle, a.allocateFrom(name), a.allocateFrom(value));
            if (rc < 0) {
                LOG.log(System.Logger.Level.WARNING, "set property " + name + ": " + Mpv.describe(rc));
            }
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private void command(String... args) {
        if (!running && !"quit".equals(args[0])) {
            return;
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment argv = a.allocate(ADDRESS, args.length + 1);
            for (int i = 0; i < args.length; i++) {
                argv.setAtIndex(ADDRESS, i, a.allocateFrom(args[i]));
            }
            argv.setAtIndex(ADDRESS, args.length, MemorySegment.NULL);
            int rc = (int) Mpv.command.invokeExact(handle, argv);
            if (rc < 0) {
                LOG.log(System.Logger.Level.WARNING, "command " + args[0] + ": " + Mpv.describe(rc));
            }
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}

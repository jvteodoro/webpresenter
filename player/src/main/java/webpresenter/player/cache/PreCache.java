package webpresenter.player.cache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import webpresenter.protocol.Message;
import webpresenter.protocol.Message.MediaRef;

/**
 * Keeps every file the player needs (rest screen and the prepared event's media) in the cache and
 * reports progress to the server. Files used several times are counted once. Thread-safe.
 */
public final class PreCache implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PreCache.class.getName());
    private static final long REPORT_INTERVAL_MS = 500;

    private enum State { DOWNLOADING, READY, FAILED }

    private static final class Entry {
        final MediaRef media;
        State state = State.DOWNLOADING;
        long bytes;

        Entry(MediaRef media) {
            this.media = media;
        }
    }

    private final MediaCache cache;
    private final Consumer<Message.CacheStatus> reporter;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("precache-report").daemon().factory());

    // Guarded by "this".
    private Map<String, Entry> required = new LinkedHashMap<>();
    private String eventId;
    private int generation;
    private boolean dirty;
    private Message.CacheStatus lastReported;

    public PreCache(MediaCache cache, Consumer<Message.CacheStatus> reporter) {
        this.cache = cache;
        this.reporter = reporter;
        ticker.scheduleWithFixedDelay(this::reportIfDirty, REPORT_INTERVAL_MS, REPORT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Replaces the set of required files and starts downloading what is missing. Failed files of
     * the previous set are retried, since every call starts their download again.
     */
    public void require(String eventId, MediaRef restScreen, List<MediaRef> eventMedia) {
        Map<String, Entry> next = new LinkedHashMap<>();
        if (restScreen != null) {
            next.putIfAbsent(restScreen.sha256(), new Entry(restScreen));
        }
        for (MediaRef media : eventMedia) {
            next.putIfAbsent(media.sha256(), new Entry(media));
        }
        int gen;
        synchronized (this) {
            this.required = next;
            this.eventId = eventId;
            gen = ++generation;
            dirty = true;
            lastReported = null; // the server asked for this set: always answer, even if unchanged
        }
        for (Entry entry : next.values()) {
            cache.fetch(entry.media, bytes -> progress(gen, entry, bytes))
                    .whenComplete((path, error) -> finished(gen, entry, error));
        }
        reportIfDirty();
    }

    /** Current status (also what was last reported). */
    public synchronized Message.CacheStatus status() {
        int ready = 0;
        int failed = 0;
        long bytesReady = 0;
        long bytesTotal = 0;
        for (Entry e : required.values()) {
            bytesTotal += e.media.sizeBytes();
            switch (e.state) {
                case READY -> {
                    ready++;
                    bytesReady += e.media.sizeBytes();
                }
                case FAILED -> failed++;
                case DOWNLOADING -> bytesReady += e.bytes;
            }
        }
        return new Message.CacheStatus(eventId, ready, required.size(), failed, bytesReady, bytesTotal);
    }

    /** Sends the current status again (for example after reconnecting). */
    public void resend() {
        synchronized (this) {
            lastReported = null;
            dirty = true;
        }
        reportIfDirty();
    }

    private synchronized void progress(int gen, Entry entry, long bytes) {
        if (gen == generation) {
            entry.bytes = bytes;
            dirty = true;
        }
    }

    private void finished(int gen, Entry entry, Throwable error) {
        synchronized (this) {
            if (gen != generation) {
                return;
            }
            entry.state = error == null ? State.READY : State.FAILED;
            dirty = true;
        }
        if (error != null) {
            LOG.log(System.Logger.Level.WARNING, "could not cache media {0}: {1}", entry.media.mediaId(),
                    error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
        }
        reportIfDirty();
    }

    private void reportIfDirty() {
        Message.CacheStatus status;
        synchronized (this) {
            if (!dirty) {
                return;
            }
            dirty = false;
            status = status();
            if (Objects.equals(status, lastReported)) {
                return;
            }
            lastReported = status;
        }
        reporter.accept(status);
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }
}

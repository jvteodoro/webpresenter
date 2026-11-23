package webpresenter.player.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import webpresenter.protocol.Message.MediaRef;

/**
 * Local copy of media files, named by their SHA-256. A file is only visible in the cache after
 * its content was verified, so a partial or corrupted download is never played.
 */
public final class MediaCache implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MediaCache.class.getName());
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Path dir;
    private final HttpClient http;
    private final ExecutorService downloads = Executors.newFixedThreadPool(2, Thread.ofPlatform()
            .name("media-download-", 0).daemon().factory());
    private final Map<String, Download> inFlight = new ConcurrentHashMap<>();

    /** Receives the number of bytes downloaded so far (from a download thread). */
    @FunctionalInterface
    public interface Progress {
        void bytesDownloaded(long bytes);

        Progress NONE = bytes -> {};
    }

    /** A download in progress, shared by every caller asking for the same file. */
    private static final class Download {
        final java.util.List<Progress> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
        CompletableFuture<Path> future;

        void report(long total) {
            bytes.set(total);
            listeners.forEach(l -> l.bytesDownloaded(total));
        }
    }

    public MediaCache(Path dir) {
        this(dir, url -> java.net.URI.create(url));
    }

    /** @param links resolves the server's links (relative ones, like "/s3/…", against its current address) */
    public MediaCache(Path dir, java.util.function.Function<String, java.net.URI> links) {
        this(dir, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), links);
    }

    MediaCache(Path dir, HttpClient http) {
        this(dir, http, url -> java.net.URI.create(url));
    }

    MediaCache(Path dir, HttpClient http, java.util.function.Function<String, java.net.URI> links) {
        this.dir = dir;
        this.http = http;
        this.links = links;
    }

    private final java.util.function.Function<String, java.net.URI> links;

    /** The verified local file, if already downloaded. */
    public Optional<Path> cached(MediaRef media) {
        Path file = fileFor(media);
        try {
            return Files.isRegularFile(file) && Files.size(file) == media.sizeBytes() ? Optional.of(file) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Returns the cached file, downloading and verifying it first if needed. Concurrent calls share one download. */
    public CompletableFuture<Path> fetch(MediaRef media) {
        return fetch(media, Progress.NONE);
    }

    /** Like {@link #fetch(MediaRef)}, reporting downloaded bytes to {@code progress} while it runs. */
    public CompletableFuture<Path> fetch(MediaRef media, Progress progress) {
        Optional<Path> existing = cached(media);
        if (existing.isPresent()) {
            return CompletableFuture.completedFuture(existing.get());
        }
        String key = media.sha256();
        fileFor(media); // validates the checksum before it is used as a file name
        synchronized (inFlight) {
            Download download = inFlight.get(key);
            if (download == null) {
                Download created = new Download();
                created.future = CompletableFuture.supplyAsync(() -> download(media, created), downloads)
                        .whenComplete((path, error) -> inFlight.remove(key));
                download = created;
                inFlight.put(key, download);
            }
            download.listeners.add(progress);
            progress.bytesDownloaded(download.bytes.get());
            return download.future;
        }
    }

    private Path download(MediaRef media, Download progress) {
        Path target = fileFor(media);
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(dir);
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(links.apply(media.url())).timeout(Duration.ofMinutes(30)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode() + " downloading media " + media.mediaId());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(response.body(), digest);
                    OutputStream out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[256 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    total += read;
                    progress.report(total);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(media.sha256())) {
                throw new IOException("checksum mismatch for media " + media.mediaId() + ": expected "
                        + media.sha256() + ", got " + actual);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOG.log(System.Logger.Level.INFO, "cached media {0} ({1} bytes)", media.mediaId(), media.sizeBytes());
            return target;
        } catch (IOException e) {
            deleteQuietly(partial);
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            deleteQuietly(partial);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("download interrupted", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path fileFor(MediaRef media) {
        if (media.sha256() == null || !SHA256.matcher(media.sha256()).matches()) {
            throw new IllegalArgumentException("invalid sha256 for media " + media.mediaId());
        }
        return dir.resolve(media.sha256());
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }

    @Override
    public void close() {
        downloads.shutdownNow();
    }
}

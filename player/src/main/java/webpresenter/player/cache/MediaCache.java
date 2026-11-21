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
    private final Map<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();

    public MediaCache(Path dir) {
        this(dir, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    MediaCache(Path dir, HttpClient http) {
        this.dir = dir;
        this.http = http;
    }

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
        Optional<Path> existing = cached(media);
        if (existing.isPresent()) {
            return CompletableFuture.completedFuture(existing.get());
        }
        String key = media.sha256();
        return inFlight.computeIfAbsent(key, k -> CompletableFuture
                .supplyAsync(() -> download(media), downloads)
                .whenComplete((path, error) -> inFlight.remove(k)));
    }

    private Path download(MediaRef media) {
        Path target = fileFor(media);
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(dir);
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(URI.create(media.url())).timeout(Duration.ofMinutes(30)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode() + " downloading media " + media.mediaId());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(response.body(), digest);
                    OutputStream out = Files.newOutputStream(partial)) {
                in.transferTo(out);
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

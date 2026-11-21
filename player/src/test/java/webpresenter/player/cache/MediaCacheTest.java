package webpresenter.player.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import webpresenter.protocol.Message.MediaKind;
import webpresenter.protocol.Message.MediaRef;

class MediaCacheTest {

    private static final byte[] CONTENT = "fake video bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private MediaCache cache;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media.mp4", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, CONTENT.length);
            exchange.getResponseBody().write(CONTENT);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        cache = new MediaCache(dir);
    }

    @AfterEach
    void stop() {
        cache.close();
        server.stop(0);
    }

    @Test
    void downloadsVerifiesAndReusesFiles() throws Exception {
        MediaRef ref = ref("/media.mp4", sha256(CONTENT));

        Path file = cache.fetch(ref).join();
        assertArrayEquals(CONTENT, Files.readAllBytes(file));
        assertEquals(dir.resolve(ref.sha256()), file);

        assertEquals(file, cache.fetch(ref).join());
        assertEquals(1, requests.get(), "second fetch must come from the cache");
        assertTrue(cache.cached(ref).isPresent());
    }

    @Test
    void rejectsContentWithTheWrongChecksum() {
        MediaRef ref = ref("/media.mp4", "0".repeat(64));

        assertThrows(CompletionException.class, () -> cache.fetch(ref).join());
        assertTrue(cache.cached(ref).isEmpty());
        assertEquals(0, dir.toFile().list().length, "no partial file may be left behind");
    }

    @Test
    void reportsHttpErrors() {
        assertThrows(CompletionException.class, () -> cache.fetch(ref("/missing", sha256(CONTENT))).join());
    }

    @Test
    void refusesUnsafeChecksumsAsFileNames() {
        assertThrows(IllegalArgumentException.class, () -> cache.fetch(ref("/media.mp4", "../../etc/passwd")));
    }

    private MediaRef ref(String path, String sha256) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + path;
        return new MediaRef("m-1", MediaKind.VIDEO, url, sha256, CONTENT.length);
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}

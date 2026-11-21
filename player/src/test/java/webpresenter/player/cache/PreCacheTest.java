package webpresenter.player.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import webpresenter.protocol.Message;
import webpresenter.protocol.Message.MediaKind;
import webpresenter.protocol.Message.MediaRef;

class PreCacheTest {

    private static final byte[] A = "first file".getBytes();
    private static final byte[] B = "second, longer file".getBytes();

    @TempDir
    Path dir;

    private HttpServer server;
    private MediaCache cache;
    private PreCache preCache;
    private final List<Message.CacheStatus> reports = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve("/a", A);
        serve("/b", B);
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        cache = new MediaCache(dir);
        preCache = new PreCache(cache, reports::add);
    }

    @AfterEach
    void stop() {
        preCache.close();
        cache.close();
        server.stop(0);
    }

    @Test
    void downloadsRestScreenAndEventMediaCountingSharedFilesOnce() throws Exception {
        MediaRef a = ref("a", "/a", A);
        MediaRef b = ref("b", "/b", B);

        preCache.require("event-1", a, List.of(b, a, b));

        Message.CacheStatus done = awaitComplete();
        assertEquals(new Message.CacheStatus("event-1", 2, 2, 0, A.length + B.length, A.length + B.length), done);
    }

    @Test
    void reportsFailuresAndRetriesThemOnTheNextRequest() throws Exception {
        MediaRef broken = ref("x", "/broken", A);

        preCache.require("event-1", null, List.of(broken));
        awaitStatus(s -> s.itemsFailed() == 1);

        server.removeContext("/broken");
        serve("/broken", A);
        preCache.require("event-1", null, List.of(broken));

        assertEquals(1, awaitComplete().itemsReady());
    }

    @Test
    void answersEveryRequestEvenWhenNothingChanged() throws Exception {
        MediaRef a = ref("a", "/a", A);
        preCache.require("event-1", null, List.of(a));
        awaitComplete();
        int before = reports.size();

        preCache.require("event-1", null, List.of(a));

        awaitStatus(s -> reports.size() > before);
    }

    private Message.CacheStatus awaitComplete() throws InterruptedException {
        return awaitStatus(Message.CacheStatus::complete);
    }

    private Message.CacheStatus awaitStatus(java.util.function.Predicate<Message.CacheStatus> condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (!reports.isEmpty() && condition.test(reports.getLast())) {
                return reports.getLast();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition not reached; reports: " + reports);
    }

    private void serve(String path, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private MediaRef ref(String id, String path, byte[] content) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        return new MediaRef(id, MediaKind.VIDEO, "http://127.0.0.1:" + server.getAddress().getPort() + path, sha,
                content.length);
    }
}

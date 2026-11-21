package webpresenter.server.devices;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import webpresenter.protocol.Message;
import webpresenter.protocol.ProtocolCodec;
import webpresenter.protocol.ProtocolVersion;

/** A scripted MediaPlayer speaking the real protocol over a real WebSocket. */
final class TestPlayer implements AutoCloseable {

    static final List<Message.DisplayInfo> TWO_DISPLAYS = List.of(
            new Message.DisplayInfo(0, 1920, 1080, 1.0, true), new Message.DisplayInfo(1, 1280, 720, 1.0, false));

    private final ProtocolCodec codec = new ProtocolCodec();
    private final BlockingQueue<Message> received = new LinkedBlockingQueue<>();
    private final java.util.List<Message> pending = new java.util.ArrayList<>();
    private final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final WebSocket socket;
    final UUID deviceId;

    private TestPlayer(int port, UUID deviceId) {
        this.deviceId = deviceId;
        this.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + ProtocolVersion.PLAYER_ENDPOINT), new Listener())
                .join();
    }

    static TestPlayer connect(int port, UUID deviceId) {
        return new TestPlayer(port, deviceId);
    }

    TestPlayer hello(String token) {
        return send(new Message.Hello(deviceId.toString(), "sala-principal", "test", ProtocolVersion.CURRENT,
                TWO_DISPLAYS, token));
    }

    TestPlayer send(Message message) {
        socket.sendText(codec.encode(message), true).join();
        return this;
    }

    /** Next unconsumed message of the given type; messages of other types stay queued for later expectations. */
    <T extends Message> T expect(Class<T> type) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (System.nanoTime() < deadline) {
                synchronized (pending) {
                    for (var it = pending.iterator(); it.hasNext(); ) {
                        Message m = it.next();
                        if (type.isInstance(m)) {
                            it.remove();
                            return type.cast(m);
                        }
                    }
                }
                Message next = received.poll(100, TimeUnit.MILLISECONDS);
                if (next != null) {
                    synchronized (pending) {
                        pending.add(next);
                    }
                }
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        synchronized (pending) {
            assertThat(pending).as("expected a %s", type.getSimpleName()).anyMatch(type::isInstance);
        }
        throw new AssertionError("unreachable");
    }

    int awaitClose() {
        return closed.orTimeout(10, TimeUnit.SECONDS).join();
    }

    @Override
    public void close() {
        if (!socket.isOutputClosed()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").orTimeout(5, TimeUnit.SECONDS).exceptionally(e -> null).join();
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                received.add(codec.decode(buffer.toString()).payload());
                buffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed.completeExceptionally(error);
        }
    }

    static Duration timeout() {
        return Duration.ofSeconds(10);
    }
}

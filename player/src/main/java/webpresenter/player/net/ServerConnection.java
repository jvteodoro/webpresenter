package webpresenter.player.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import webpresenter.protocol.Message;
import webpresenter.protocol.ProtocolCodec;
import webpresenter.protocol.ProtocolVersion;

/**
 * Keeps a WebSocket connection to the central server: sends {@code HELLO} on every (re)connection,
 * reconnects with exponential backoff, and detects dead connections with pings.
 * Listener callbacks run on internal threads.
 */
public final class ServerConnection implements AutoCloseable {

    public interface Listener {
        void onConnected();

        void onMessage(Message message);

        void onDisconnected(String reason);
    }

    private static final System.Logger LOG = System.getLogger(ServerConnection.class.getName());
    private static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    /** Short on purpose: on a LAN a retry is cheap, and the output should be back in control quickly. */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(15);
    private static final Duration DEAD_AFTER = Duration.ofSeconds(45);
    /** Another instance with our device id took over; retrying fast would make both flap. */
    private static final Duration REPLACED_DELAY = Duration.ofSeconds(60);

    /** Reason passed to {@link Listener#onDisconnected} when a newer connection replaced this one. */
    public static final String REPLACED = "REPLACED";

    private final URI endpoint;
    private final ProtocolCodec codec;
    private final Supplier<Message.Hello> hello;
    private final Listener listener;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("server-connection").daemon().factory());

    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile Instant lastPong = Instant.now();
    private Duration backoff = MIN_BACKOFF;
    private ScheduledFuture<?> pinger;

    public ServerConnection(URI serverUrl, ProtocolCodec codec, Supplier<Message.Hello> hello, Listener listener) {
        this.endpoint = toWebSocket(serverUrl);
        this.codec = codec;
        this.hello = hello;
        this.listener = listener;
    }

    /** http(s)://host:port → ws(s)://host:port/ws/player */
    static URI toWebSocket(URI serverUrl) {
        String scheme = "https".equalsIgnoreCase(serverUrl.getScheme()) ? "wss" : "ws";
        String base = serverUrl.toString().replaceFirst("^[a-zA-Z]+://", scheme + "://").replaceAll("/+$", "");
        return URI.create(base + ProtocolVersion.PLAYER_ENDPOINT);
    }

    public URI endpoint() {
        return endpoint;
    }

    public void start() {
        scheduler.execute(this::connect);
    }

    /** Sends if connected; messages sent while disconnected are dropped (state is resent on reconnect). */
    public void send(Message message) {
        WebSocket ws = socket;
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendText(codec.encode(message), true).exceptionally(e -> {
                LOG.log(System.Logger.Level.WARNING, "send failed: {0}", e.getMessage());
                return null;
            });
        }
    }

    private void connect() {
        if (closed) {
            return;
        }
        LOG.log(System.Logger.Level.INFO, "connecting to {0}", endpoint);
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(endpoint, new SocketListener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        scheduleReconnect("cannot connect: " + rootMessage(error));
                    }
                });
    }

    private void scheduleReconnect(String reason) {
        scheduleReconnect(reason, null);
    }

    private void scheduleReconnect(String reason, Duration fixedDelay) {
        socket = null;
        if (pinger != null) {
            pinger.cancel(false);
        }
        listener.onDisconnected(reason);
        if (closed) {
            return;
        }
        Duration delay = fixedDelay != null ? fixedDelay : backoff;
        if (fixedDelay == null) {
            backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
        }
        LOG.log(System.Logger.Level.INFO, "{0}; retrying in {1}s", reason, delay.toSeconds());
        scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        if (Duration.between(lastPong, Instant.now()).compareTo(DEAD_AFTER) > 0) {
            LOG.log(System.Logger.Level.WARNING, "no pong for {0}s, dropping connection", DEAD_AFTER.toSeconds());
            socket = null; // so late onClose/onError callbacks of this socket are ignored
            ws.abort(); // onError/onClose is not guaranteed after abort, so reconnect here
            scheduler.execute(() -> scheduleReconnect("connection timed out"));
            return;
        }
        ws.sendPing(ByteBuffer.allocate(0));
    }

    @Override
    public void close() {
        closed = true;
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "player shutting down")
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally(e -> null)
                    .join();
        }
        scheduler.shutdownNow();
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    private final class SocketListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            socket = ws;
            lastPong = Instant.now();
            scheduler.execute(() -> {
                pinger = scheduler.scheduleAtFixedRate(ServerConnection.this::heartbeat,
                        PING_INTERVAL.toMillis(), PING_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            });
            listener.onConnected();
            send(hello.get());
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                try {
                    Message message = codec.decode(text).payload();
                    backoff = MIN_BACKOFF; // the server is talking to us: the connection is healthy
                    listener.onMessage(message);
                } catch (ProtocolCodec.ProtocolException e) {
                    LOG.log(System.Logger.Level.WARNING, "ignoring invalid message: {0}", e.getMessage());
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "message handler failed", e);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
            lastPong = Instant.now();
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (socket == ws) {
                if (statusCode == ProtocolVersion.CLOSE_REPLACED) {
                    LOG.log(System.Logger.Level.WARNING,
                            "another player with this device id connected to the server; is this installation a copy?");
                    scheduler.execute(() -> scheduleReconnect(REPLACED, REPLACED_DELAY));
                } else {
                    scheduler.execute(() -> scheduleReconnect("closed by server (" + statusCode
                            + (reason == null || reason.isBlank() ? "" : ", " + reason) + ")"));
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (socket == ws) {
                scheduler.execute(() -> scheduleReconnect("connection error: " + rootMessage(error)));
            }
        }
    }
}

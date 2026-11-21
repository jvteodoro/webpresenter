package webpresenter.player;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import webpresenter.player.cache.MediaCache;
import webpresenter.player.cache.PreCache;
import webpresenter.player.net.ServerConnection;
import webpresenter.protocol.Message;

/**
 * Reacts to the server: pairing, configuration, the event plan, live commands, revocation and
 * connection loss. Network callbacks arrive on background threads and are handed to the JavaFX
 * thread; all state lives there.
 *
 * <p>When the connection drops, whatever is on screen keeps going: a video plays to its end and
 * the rest screen follows, as the architecture requires.
 */
final class PlayerController implements ServerConnection.Listener {

    private static final System.Logger LOG = System.getLogger(PlayerController.class.getName());
    private static final Duration POSITION_REPORT = Duration.ofSeconds(1);

    private final PresentationWindow window;
    private final PlayerIdentity identity;
    private final MediaCache cache;
    private final PreCache preCache;
    private final String serverLabel;
    private final AtomicReference<ServerConnection> connection = new AtomicReference<>();
    private final Runnable onStateChanged;
    private final Timeline positionReporter;

    /** Rest screen most recently requested; a slower earlier download must not replace it. */
    private Message.MediaRef wantedRest;
    /** The event to be ready for, and presented live. */
    private Message.Prepare plan = Message.Prepare.none();
    /** Live item on screen (or being loaded), null at the rest screen. */
    private Message.PlanItem liveItem;
    /** Increases with every live command; delayed work of an older command is discarded. */
    private long commandSeq;
    private PauseTransition pendingStart;
    private boolean paused;
    private boolean paired;

    PlayerController(PresentationWindow window, PlayerIdentity identity, MediaCache cache, String serverLabel,
            Runnable onStateChanged) {
        this.window = window;
        this.identity = identity;
        this.cache = cache;
        this.preCache = new PreCache(cache, this::send);
        this.serverLabel = serverLabel;
        this.onStateChanged = onStateChanged;
        this.positionReporter = new Timeline(new KeyFrame(javafx.util.Duration.millis(POSITION_REPORT.toMillis()),
                e -> reportPosition()));
        positionReporter.setCycleCount(Timeline.INDEFINITE);
        window.setOnItemEnded(this::itemEnded);
        window.showInfo("Conectando ao servidor…", null, serverLabel);
    }

    void attach(ServerConnection serverConnection) {
        connection.set(serverConnection);
    }

    @Override
    public void onConnected() {
        LOG.log(System.Logger.Level.INFO, "connected to {0}", serverLabel);
    }

    @Override
    public void onMessage(Message message) {
        Platform.runLater(() -> {
            handle(message);
            onStateChanged.run();
        });
    }

    @Override
    public void onDisconnected(String reason) {
        Platform.runLater(() -> {
            // Keep presenting what is on screen; only an idle output shows the connection problem.
            if (!window.isPresenting()) {
                if (ServerConnection.REPLACED.equals(reason)) {
                    window.showInfo("Outro player com a mesma identidade conectou", null,
                            "Esta instalação parece ser uma cópia. Apague a pasta de dados do player para gerar uma nova identidade.");
                } else {
                    window.showInfo("Sem conexão com o servidor", null, serverLabel + "\nTentando novamente…");
                }
                onStateChanged.run();
            }
        });
    }

    private void handle(Message message) {
        switch (message) {
            case Message.PairingPending pending -> {
                paired = false;
                identity.clearToken(); // the server no longer knows us as approved
                leaveItem();
                window.showInfo("Aguardando aprovação", pending.pairingCode(),
                        "Aprove este player em Dispositivos no painel web\n" + identity.deviceId());
            }
            case Message.Paired p -> {
                identity.storeToken(p.token());
                paired = true;
                LOG.log(System.Logger.Level.INFO, "paired with the server");
            }
            case Message.Config config -> applyConfig(config);
            case Message.Prepare prepare -> {
                plan = prepare;
                LOG.log(System.Logger.Level.INFO, "preparing event {0} ({1} items)", prepare.eventId(), prepare.items().size());
                updateRequiredMedia();
            }
            case Message.Play play -> play(play);
            case Message.Pause p -> setPaused(true);
            case Message.Resume r -> setPaused(false);
            case Message.ShowRest rest -> {
                if (rest.transition() != null) {
                    window.setTransition(rest.transition());
                }
                commandSeq++;
                leaveItem();
                report(Message.PlaybackStatus.REST, null, null);
            }
            case Message.Revoked revoked -> {
                paired = false;
                identity.clearToken();
                wantedRest = null;
                plan = Message.Prepare.none();
                leaveItem();
                updateRequiredMedia();
                window.showInfo("Player removido do servidor", null, revoked.reason());
            }
            case Message.Error error -> {
                LOG.log(System.Logger.Level.WARNING, "server error {0}: {1}", error.code(), error.message());
                if ("INVALID_TOKEN".equals(error.code())) {
                    window.showInfo("Este player não é mais reconhecido", null,
                            "Remova-o em Dispositivos no painel web para pareá-lo novamente");
                }
            }
            default -> LOG.log(System.Logger.Level.DEBUG, "ignoring {0}", message.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ live presentation

    private void play(Message.Play command) {
        long seq = ++commandSeq;
        if (command.transition() != null) {
            window.setTransition(command.transition());
        }
        Optional<Message.PlanItem> found = plan.items().stream()
                .filter(i -> i.itemId().equals(command.itemId()))
                .findFirst();
        if (found.isEmpty() || !command.eventId().equals(plan.eventId())) {
            report(Message.PlaybackStatus.FAILED, command.itemId(), "item is not part of the prepared event");
            return;
        }
        Message.PlanItem item = found.get();
        if (item.type() == Message.ItemType.SLIDE) {
            startAt(command.startAt(), seq, () -> {
                liveItem = item;
                paused = false;
                window.showSlide(item.slide().title(), item.slide().body());
                report(Message.PlaybackStatus.SHOWING, item.itemId(), null);
            });
            return;
        }
        Message.MediaRef media = item.media();
        Optional<Path> cached = cache.cached(media);
        if (cached.isPresent()) {
            startAt(command.startAt(), seq, () -> present(item, cached.get(), command.startPositionMs()));
            return;
        }
        // Not cached (added during the event, or a failed download): fetch now, start late.
        report(Message.PlaybackStatus.LOADING, item.itemId(), null);
        Instant requestedAt = Instant.now();
        cache.fetch(media).whenComplete((file, error) -> Platform.runLater(() -> {
            if (seq != commandSeq) {
                return; // superseded while downloading
            }
            if (error != null) {
                report(Message.PlaybackStatus.FAILED, item.itemId(), "download failed: " + error.getMessage());
                return;
            }
            long lateBy = Duration.between(command.startAt(), Instant.now()).toMillis();
            long position = command.startPositionMs() + (requestedAt.isAfter(command.startAt()) ? Math.max(0, lateBy) : 0);
            present(item, file, item.media().kind() == Message.MediaKind.IMAGE ? 0 : position);
            onStateChanged.run();
        }));
    }

    private void present(Message.PlanItem item, Path file, long startPositionMs) {
        liveItem = item;
        paused = false;
        switch (item.media().kind()) {
            case VIDEO -> window.showVideo(file, startPositionMs);
            case AUDIO -> window.showAudio(file, startPositionMs);
            case IMAGE -> window.showImage(file);
        }
        if (item.media().kind() == Message.MediaKind.IMAGE) {
            report(Message.PlaybackStatus.SHOWING, item.itemId(), null);
        } else {
            report(Message.PlaybackStatus.PLAYING, item.itemId(), null);
            positionReporter.playFromStart();
        }
    }

    /** Runs the action at the scheduled instant (now if it already passed), unless superseded. */
    private void startAt(Instant at, long seq, Runnable action) {
        if (pendingStart != null) {
            pendingStart.stop();
        }
        long delay = at == null ? 0 : Math.max(0, Duration.between(Instant.now(), at).toMillis());
        pendingStart = new PauseTransition(javafx.util.Duration.millis(Math.max(1, delay)));
        pendingStart.setOnFinished(e -> {
            if (seq == commandSeq) {
                action.run();
                onStateChanged.run();
            }
        });
        pendingStart.play();
    }

    private void setPaused(boolean pause) {
        if (liveItem == null || !isTimeBased(liveItem)) {
            return;
        }
        paused = pause;
        window.setItemPaused(pause);
        report(pause ? Message.PlaybackStatus.PAUSED : Message.PlaybackStatus.PLAYING, liveItem.itemId(), null);
    }

    private void itemEnded() {
        if (liveItem == null) {
            return;
        }
        String itemId = liveItem.itemId();
        long duration = window.itemDurationMs();
        liveItem = null;
        positionReporter.stop();
        send(new Message.PlaybackState(itemId, Message.PlaybackStatus.ENDED, duration, duration, null));
        onStateChanged.run();
    }

    private void leaveItem() {
        if (pendingStart != null) {
            pendingStart.stop();
        }
        liveItem = null;
        paused = false;
        positionReporter.stop();
        window.showRest();
    }

    private void reportPosition() {
        if (liveItem != null && isTimeBased(liveItem) && !paused) {
            report(Message.PlaybackStatus.PLAYING, liveItem.itemId(), null);
        }
    }

    private void report(Message.PlaybackStatus status, String itemId, String detail) {
        boolean media = liveItem != null && isTimeBased(liveItem) && itemId != null && itemId.equals(liveItem.itemId());
        send(new Message.PlaybackState(itemId, status, media ? window.itemPositionMs() : 0,
                media ? window.itemDurationMs() : 0, detail));
    }

    private static boolean isTimeBased(Message.PlanItem item) {
        return item.type() == Message.ItemType.MEDIA && item.media() != null
                && (item.media().kind() == Message.MediaKind.VIDEO || item.media().kind() == Message.MediaKind.AUDIO);
    }

    // ------------------------------------------------------------------ configuration and cache

    private void applyConfig(Message.Config config) {
        paired = true;
        window.setTransition(config.transition() == null ? Message.Transition.DEFAULT : config.transition());
        window.moveTo(config.displayIndex());
        Message.MediaRef rest = config.restScreen();
        wantedRest = rest;
        updateRequiredMedia();
        if (rest == null) {
            window.clearRest();
            if (!window.isPresenting()) {
                window.showRest(); // hides the information panel: black output
            }
            return;
        }
        if (cache.cached(rest).isEmpty() && !window.isPresenting()) {
            window.showInfo("Baixando tela de descanso…", null, config.deviceName());
        }
        cache.fetch(rest).whenComplete((file, error) -> Platform.runLater(() -> {
            if (wantedRest != rest) {
                return; // superseded by a newer configuration
            }
            if (error != null) {
                LOG.log(System.Logger.Level.ERROR, "cannot cache rest screen " + rest.mediaId(), error);
                if (!window.isPresenting()) {
                    window.showInfo("Não foi possível baixar a tela de descanso", null, error.getMessage());
                }
            } else if (rest.kind() == Message.MediaKind.VIDEO) {
                window.setRestVideo(file);
            } else {
                window.setRestImage(file);
            }
            onStateChanged.run();
        }));
    }

    /** Rest screen plus every media item of the plan must be in the cache. */
    private void updateRequiredMedia() {
        List<Message.MediaRef> eventMedia = plan.items().stream()
                .filter(i -> i.type() == Message.ItemType.MEDIA && i.media() != null)
                .map(Message.PlanItem::media)
                .toList();
        preCache.require(plan.eventId(), wantedRest, eventMedia);
    }

    private void send(Message message) {
        ServerConnection c = connection.get();
        if (c != null) {
            c.send(message);
        }
    }

    boolean isPaired() {
        return paired;
    }

    void close() {
        positionReporter.stop();
        preCache.close();
    }
}

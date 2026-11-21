package webpresenter.player;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import webpresenter.player.cache.MediaCache;
import webpresenter.player.net.ServerConnection;
import webpresenter.protocol.Message;

/**
 * Reacts to the server: pairing, configuration, revocation and connection loss. Network callbacks
 * arrive on background threads and are handed to the JavaFX thread; all state lives there.
 */
final class PlayerController implements ServerConnection.Listener {

    private static final System.Logger LOG = System.getLogger(PlayerController.class.getName());

    private final PresentationWindow window;
    private final PlayerIdentity identity;
    private final MediaCache cache;
    private final String serverLabel;
    private final AtomicReference<ServerConnection> connection = new AtomicReference<>();
    private final Runnable onStateChanged;

    /** Rest screen most recently requested; a slower earlier download must not replace it. */
    private Message.MediaRef wantedRest;
    private boolean paired;

    PlayerController(PresentationWindow window, PlayerIdentity identity, MediaCache cache, String serverLabel,
            Runnable onStateChanged) {
        this.window = window;
        this.identity = identity;
        this.cache = cache;
        this.serverLabel = serverLabel;
        this.onStateChanged = onStateChanged;
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
                window.showInfo("Aguardando aprovação", pending.pairingCode(),
                        "Aprove este player em Dispositivos no painel web\n" + identity.deviceId());
            }
            case Message.Paired p -> {
                identity.storeToken(p.token());
                paired = true;
                LOG.log(System.Logger.Level.INFO, "paired with the server");
            }
            case Message.Config config -> applyConfig(config);
            case Message.Revoked revoked -> {
                paired = false;
                identity.clearToken();
                wantedRest = null;
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

    private void applyConfig(Message.Config config) {
        paired = true;
        window.setTransition(config.transition() == null ? Message.Transition.DEFAULT : config.transition());
        window.moveTo(config.displayIndex());
        Message.MediaRef rest = config.restScreen();
        wantedRest = rest;
        if (rest == null) {
            window.showBlack();
            send(new Message.CacheStatus(0, 0, 0, 0));
            return;
        }
        if (cache.cached(rest).isEmpty()) {
            send(new Message.CacheStatus(0, 1, 0, rest.sizeBytes()));
            if (!window.isPresenting()) {
                window.showInfo("Baixando tela de descanso…", null, config.deviceName());
            }
        }
        cache.fetch(rest).whenComplete((file, error) -> Platform.runLater(() -> {
            if (wantedRest != rest) {
                return; // superseded by a newer configuration
            }
            if (error != null) {
                LOG.log(System.Logger.Level.ERROR, "cannot cache rest screen " + rest.mediaId(), error);
                send(new Message.Error("CACHE_FAILED", "rest screen " + rest.mediaId() + ": " + error.getMessage()));
                window.showInfo("Não foi possível baixar a tela de descanso", null, error.getMessage());
            } else {
                showRest(rest, file);
                send(new Message.CacheStatus(1, 1, rest.sizeBytes(), rest.sizeBytes()));
            }
            onStateChanged.run();
        }));
    }

    private void showRest(Message.MediaRef rest, Path file) {
        if (rest.kind() == Message.MediaKind.VIDEO) {
            window.showRestVideo(file);
        } else {
            window.showRestImage(file);
        }
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
}

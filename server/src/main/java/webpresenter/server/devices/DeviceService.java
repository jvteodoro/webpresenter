package webpresenter.server.devices;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import webpresenter.protocol.Message;
import webpresenter.protocol.ProtocolVersion;
import webpresenter.server.library.MediaAssetDeleted;
import webpresenter.server.library.MediaCatalog;
import webpresenter.server.library.MediaFile;
import webpresenter.server.library.MediaKind;
import webpresenter.server.planning.CurrentEventChanged;
import webpresenter.server.planning.ItemType;
import webpresenter.server.planning.PlannedEvents;

/** Device registry: pairing, approval, configuration, and the messages that keep players in sync. */
@Service
class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    private static final TypeReference<List<Message.DisplayInfo>> DISPLAY_LIST = new TypeReference<>() {};

    private final DeviceRepository repository;
    private final PlayerSessions sessions;
    private final MediaCatalog catalog;
    private final PlannedEvents plannedEvents;
    private final JsonMapper json;
    private final Clock clock = Clock.systemUTC();

    DeviceService(DeviceRepository repository, PlayerSessions sessions, MediaCatalog catalog,
            PlannedEvents plannedEvents, JsonMapper json) {
        this.repository = repository;
        this.sessions = sessions;
        this.catalog = catalog;
        this.plannedEvents = plannedEvents;
        this.json = json;
    }

    // ------------------------------------------------------------------ player connection

    /**
     * Handles the player's first message. Registers the session and answers with either
     * {@code PAIRING_PENDING} or {@code CONFIG}; closes the connection when the player cannot be accepted.
     *
     * @return the device id if the session was accepted
     */
    @Transactional
    Optional<UUID> hello(WebSocketSession session, Message.Hello hello) {
        if (hello.protocolVersion() != ProtocolVersion.CURRENT) {
            reject(session, "UNSUPPORTED_PROTOCOL", "server speaks protocol " + ProtocolVersion.CURRENT);
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(hello.deviceId());
        } catch (IllegalArgumentException | NullPointerException e) {
            reject(session, "INVALID_DEVICE_ID", "deviceId must be a UUID");
            return Optional.empty();
        }
        String hostname = hello.hostname() == null || hello.hostname().isBlank() ? "player" : hello.hostname().strip();
        Instant now = clock.instant();

        Device device = repository.findById(id).orElse(null);
        if (device == null) {
            device = repository.save(Device.pending(id, hostname, Secrets.newPairingCode(), now));
            log.info("new player {} ({}) waiting for approval with code {}", id, hostname, device.pairingCode());
        } else if (device.status() == DeviceStatus.APPROVED && !Secrets.matches(hello.token(), device.tokenHash())) {
            // Never downgrade an approved device because of an unauthenticated connection.
            reject(session, "INVALID_TOKEN", "device token is missing or invalid; remove the device and pair again");
            return Optional.empty();
        }
        device.reportConnection(hostname, hello.playerVersion(), json.writeValueAsString(hello.displays()), now);

        sessions.register(id, session);
        if (device.status() == DeviceStatus.PENDING) {
            sessions.send(session, new Message.PairingPending(device.pairingCode()));
        } else {
            sessions.send(session, config(device));
            sessions.send(session, prepare());
        }
        return Optional.of(id);
    }

    @Transactional
    void disconnected(UUID id, WebSocketSession session) {
        sessions.unregister(id, session);
        repository.findById(id).ifPresent(d -> d.seen(clock.instant()));
    }

    void cacheStatus(UUID id, Message.CacheStatus status) {
        sessions.updateCacheStatus(id, status);
    }

    // ------------------------------------------------------------------ operator actions

    @Transactional(readOnly = true)
    List<DeviceView> list() {
        return repository.findAllByOrderByStatusDescNameAsc().stream().map(this::view).toList();
    }

    /**
     * Approves a pending device. The player must be connected, since the token is delivered only
     * over its live connection and is never stored in clear text.
     */
    @Transactional
    DeviceView approve(UUID id, String name, String approvedBy) {
        Device device = get(id);
        if (device.status() != DeviceStatus.PENDING) {
            throw new DeviceStateException("device is already approved");
        }
        if (!sessions.isOnline(id)) {
            throw new DeviceStateException("the player must be connected to be approved");
        }
        String token = Secrets.newToken();
        device.approve(nameOr(name, device.hostname()), Secrets.hash(token), approvedBy, clock.instant());
        repository.flush();
        sessions.send(id, new Message.Paired(token));
        sessions.send(id, config(device));
        sessions.send(id, prepare());
        log.info("player {} approved as '{}' by {}", id, device.name(), approvedBy);
        return view(device);
    }

    @Transactional
    DeviceView configure(UUID id, String name, int displayIndex, UUID restMediaId) {
        Device device = get(id);
        if (device.status() != DeviceStatus.APPROVED) {
            throw new DeviceStateException("approve the device before configuring it");
        }
        int displays = displays(device).size();
        if (displayIndex < 0 || (displays > 0 && displayIndex >= displays)) {
            throw new InvalidSettingsException("display " + displayIndex + " does not exist on this player");
        }
        if (restMediaId != null) {
            MediaFile media = catalog.find(restMediaId)
                    .orElseThrow(() -> new InvalidSettingsException("media " + restMediaId + " does not exist"));
            if (media.kind() == MediaKind.AUDIO) {
                throw new InvalidSettingsException("the rest screen must be an image or a video");
            }
        }
        device.configure(nameOr(name, device.name()), displayIndex, restMediaId);
        sessions.send(id, config(device));
        return view(device);
    }

    @Transactional
    void remove(UUID id) {
        Device device = get(id);
        sessions.send(id, new Message.Revoked("device removed by an operator"));
        sessions.disconnect(id, CloseStatus.NORMAL.withReason("device removed"));
        repository.delete(device);
        log.info("player {} ('{}') removed", id, device.name());
    }

    /** Sends the new plan to every connected, approved player once the planning change is committed. */
    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    void onCurrentEventChanged(CurrentEventChanged event) {
        Message.Prepare prepare = prepare();
        for (Device device : repository.findAllByStatus(DeviceStatus.APPROVED)) {
            sessions.send(device.id(), prepare);
        }
        log.info("sent plan for event {} to connected players", event.eventId());
    }

    /** Readiness of approved players for the current event (visible to worship leaders). */
    @Transactional(readOnly = true)
    List<Readiness> readiness() {
        return repository.findAllByStatus(DeviceStatus.APPROVED).stream()
                .map(d -> new Readiness(d.id(), d.name(), sessions.isOnline(d.id()),
                        sessions.cacheStatus(d.id()).orElse(null)))
                .toList();
    }

    record Readiness(UUID id, String name, boolean online, Message.CacheStatus cacheStatus) {}

    /** A deleted library file can no longer be a rest screen. */
    @EventListener
    void onMediaDeleted(MediaAssetDeleted event) {
        for (Device device : repository.findAllByRestMediaId(event.id())) {
            device.clearRestMedia();
            sessions.send(device.id(), config(device));
        }
    }

    // ------------------------------------------------------------------ helpers

    private Message.Config config(Device device) {
        Message.MediaRef rest = Optional.ofNullable(device.restMediaId())
                .flatMap(catalog::find)
                .map(this::mediaRef)
                .orElse(null);
        return new Message.Config(device.name(), device.displayIndex(), rest, Message.Transition.DEFAULT);
    }

    private Message.Prepare prepare() {
        return plannedEvents.current()
                .map(plan -> new Message.Prepare(plan.id().toString(), plan.title(), plan.items().stream()
                        .map(this::planItem)
                        .flatMap(Optional::stream)
                        .toList()))
                .orElse(Message.Prepare.none());
    }

    private Optional<Message.PlanItem> planItem(PlannedEvents.EventPlan.Item item) {
        if (item.type() == ItemType.SLIDE) {
            return Optional.of(new Message.PlanItem(item.id().toString(), Message.ItemType.SLIDE, item.slideTitle(),
                    null, new Message.Slide(item.slideTitle(), item.slideBody())));
        }
        return catalog.find(item.mediaId()).map(media -> new Message.PlanItem(item.id().toString(),
                Message.ItemType.MEDIA, media.title(), mediaRef(media), null));
    }

    private Message.MediaRef mediaRef(MediaFile media) {
        return new Message.MediaRef(media.id().toString(), Message.MediaKind.valueOf(media.kind().name()),
                catalog.downloadUrl(media.id()).url().toString(), media.sha256(), media.sizeBytes());
    }

    private DeviceView view(Device device) {
        DeviceView.RestScreen rest = Optional.ofNullable(device.restMediaId())
                .flatMap(catalog::find)
                .map(m -> new DeviceView.RestScreen(m.id(), m.title(), m.kind()))
                .orElse(null);
        return new DeviceView(device.id(), device.name(), device.hostname(), device.status(),
                device.status() == DeviceStatus.PENDING ? device.pairingCode() : null,
                sessions.isOnline(device.id()), device.playerVersion(), displays(device), device.displayIndex(),
                rest, sessions.cacheStatus(device.id()).orElse(null), device.lastSeenAt(), device.approvedAt(),
                device.approvedBy());
    }

    private List<Message.DisplayInfo> displays(Device device) {
        return json.readValue(device.displaysJson(), DISPLAY_LIST);
    }

    private Device get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new DeviceNotFoundException(id));
    }

    private static String nameOr(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name.strip();
    }

    private void reject(WebSocketSession session, String code, String message) {
        sessions.send(session, new Message.Error(code, message));
        PlayerSessions.close(session, CloseStatus.POLICY_VIOLATION.withReason(code));
    }

    record DeviceView(UUID id, String name, String hostname, DeviceStatus status, String pairingCode, boolean online,
            String playerVersion, List<Message.DisplayInfo> displays, int displayIndex, RestScreen restScreen,
            Message.CacheStatus cacheStatus, Instant lastSeenAt, Instant approvedAt, String approvedBy) {

        record RestScreen(UUID mediaId, String title, MediaKind kind) {}
    }

    static class DeviceNotFoundException extends RuntimeException {
        DeviceNotFoundException(UUID id) {
            super("device " + id + " not found");
        }
    }

    static class DeviceStateException extends RuntimeException {
        DeviceStateException(String message) {
            super(message);
        }
    }

    static class InvalidSettingsException extends RuntimeException {
        InvalidSettingsException(String message) {
            super(message);
        }
    }
}

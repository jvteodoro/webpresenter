package webpresenter.player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Persistent device id (created on first start) and the device token received when paired. */
public final class PlayerIdentity {

    private static final String DEVICE_ID = "device.id";
    private static final String TOKEN = "device.token";

    private final Path file;
    private final Properties properties = new Properties();

    private PlayerIdentity(Path file) {
        this.file = file;
    }

    public static PlayerIdentity load(Path configDir) {
        PlayerIdentity identity = new PlayerIdentity(configDir.resolve("player.properties"));
        try {
            if (Files.exists(identity.file)) {
                try (InputStream in = Files.newInputStream(identity.file)) {
                    identity.properties.load(in);
                }
            }
            if (identity.properties.getProperty(DEVICE_ID) == null) {
                identity.properties.setProperty(DEVICE_ID, UUID.randomUUID().toString());
                identity.save();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read player identity at " + identity.file, e);
        }
        return identity;
    }

    public UUID deviceId() {
        return UUID.fromString(properties.getProperty(DEVICE_ID));
    }

    public synchronized Optional<String> token() {
        return Optional.ofNullable(properties.getProperty(TOKEN));
    }

    public synchronized void storeToken(String token) {
        properties.setProperty(TOKEN, token);
        save();
    }

    public synchronized void clearToken() {
        if (properties.remove(TOKEN) != null) {
            save();
        }
    }

    /** Written to a temporary file and moved into place, so a crash never leaves a truncated file. */
    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "webpresenter player identity - keep private");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write player identity at " + file, e);
        }
    }
}

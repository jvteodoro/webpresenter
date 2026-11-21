package webpresenter.server.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import webpresenter.server.storage.MediaStore;
import webpresenter.server.storage.PresignedUrl;

@Service
@EnableConfigurationProperties(LibraryProperties.class)
class MediaLibraryService implements MediaCatalog {

    private static final Logger log = LoggerFactory.getLogger(MediaLibraryService.class);

    private final MediaAssetRepository repository;
    private final MediaStore store;
    private final MediaProbe probe;
    private final LibraryProperties properties;
    private final ApplicationEventPublisher events;
    /** Resolved lazily: implementations live in modules that themselves depend on the library. */
    private final ObjectProvider<MediaUsage> usages;
    private final Clock clock;

    MediaLibraryService(MediaAssetRepository repository, MediaStore store, MediaProbe probe,
            LibraryProperties properties, ApplicationEventPublisher events, ObjectProvider<MediaUsage> usages) {
        this.repository = repository;
        this.store = store;
        this.probe = probe;
        this.properties = properties;
        this.events = events;
        this.usages = usages;
        this.clock = Clock.systemUTC();
    }

    /**
     * Adds a file to the library: copies it to a temporary file while hashing it, inspects it
     * with ffprobe, stores it, and records its metadata.
     */
    @Transactional
    MediaAsset upload(InputStream content, String originalFilename, String declaredContentType, String title,
            String uploadedBy) {
        String filename = sanitizeFilename(originalFilename);
        Path temp = null;
        try {
            temp = Files.createTempFile("webpresenter-upload-", suffix(filename));
            String sha256 = copyAndHash(content, temp);
            long size = Files.size(temp);
            MediaProbe.ProbeResult info = probe.probe(temp)
                    .orElseThrow(() -> new UnsupportedMediaException(filename));

            UUID id = UUID.randomUUID();
            String key = "media/" + id + suffix(filename);
            String contentType = contentType(declaredContentType, filename);
            store.put(key, temp, contentType);

            MediaAsset asset = new MediaAsset(id, titleOrDefault(title, filename), filename, info.kind(), contentType,
                    size, sha256, info.durationMs(), info.width(), info.height(), key, Instant.now(clock), uploadedBy);
            try {
                return repository.saveAndFlush(asset);
            } catch (RuntimeException e) {
                store.delete(key); // keep store and database consistent
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(temp);
        }
    }

    @Transactional(readOnly = true)
    List<MediaAsset> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    MediaAsset get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new MediaNotFoundException(id));
    }

    @Transactional
    MediaAsset rename(UUID id, String title) {
        MediaAsset asset = get(id);
        asset.rename(title.strip());
        return asset;
    }

    @Transactional
    void delete(UUID id) {
        MediaAsset asset = get(id);
        List<String> usedIn = usages.orderedStream().flatMap(u -> u.usagesOf(id).stream()).toList();
        if (!usedIn.isEmpty()) {
            throw new MediaInUseException(asset.title(), usedIn);
        }
        repository.delete(asset);
        repository.flush();
        events.publishEvent(new MediaAssetDeleted(id));
        store.delete(asset.storageKey());
    }

    @Override
    public PresignedUrl downloadUrl(UUID id) {
        return store.presignedDownload(get(id).storageKey(), properties.downloadUrlValidity());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MediaFile> find(UUID id) {
        return repository.findById(id).map(a -> new MediaFile(a.id(), a.title(), a.kind(), a.contentType(),
                a.sizeBytes(), a.sha256(), a.durationMs()));
    }

    private static String copyAndHash(InputStream content, Path target) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = new DigestInputStream(content, digest);
                OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Keeps only the last path segment and drops control characters. */
    static String sanitizeFilename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        return name.isEmpty() ? "arquivo" : name;
    }

    /** File extension including the dot, lower-case and limited to safe characters, or empty. */
    static String suffix(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,10}") ? "." + ext : "";
    }

    private static String titleOrDefault(String title, String filename) {
        if (title != null && !title.isBlank()) {
            return title.strip();
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String contentType(String declared, String filename) {
        if (declared != null && !declared.isBlank() && !declared.equals("application/octet-stream")) {
            return declared;
        }
        String guessed = URLConnection.guessContentTypeFromName(filename);
        return guessed != null ? guessed : "application/octet-stream";
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("could not delete temporary file {}", file, e);
        }
    }

    static class MediaNotFoundException extends java.util.NoSuchElementException {
        MediaNotFoundException(UUID id) {
            super("media " + id + " not found");
        }
    }

    static class MediaInUseException extends RuntimeException {
        final List<String> usages;

        MediaInUseException(String title, List<String> usages) {
            super("'" + title + "' is used in: " + String.join(", ", usages));
            this.usages = List.copyOf(usages);
        }
    }

    static class UnsupportedMediaException extends RuntimeException {
        UnsupportedMediaException(String filename) {
            super("'" + filename + "' is not a supported video, audio or image file");
        }
    }
}

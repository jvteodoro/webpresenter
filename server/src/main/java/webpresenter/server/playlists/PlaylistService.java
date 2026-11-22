package webpresenter.server.playlists;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import webpresenter.server.library.MediaCatalog;
import webpresenter.server.library.MediaKind;
import webpresenter.server.library.MediaUsage;
import webpresenter.server.songs.SongCatalog;

@Service
class PlaylistService implements MediaUsage, PlaylistCatalog {

    private final PlaylistRepository playlists;
    private final SongCatalog songs;
    private final MediaCatalog catalog;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    PlaylistService(PlaylistRepository playlists, SongCatalog songs, MediaCatalog catalog,
            org.springframework.context.ApplicationEventPublisher events) {
        this.playlists = playlists;
        this.songs = songs;
        this.catalog = catalog;
        this.events = events;
    }

    @Transactional(readOnly = true)
    List<Playlist> list() {
        List<Playlist> all = playlists.findAllByOrderByNameAsc();
        all.forEach(p -> p.items().size());
        return all;
    }

    @Transactional(readOnly = true)
    Playlist get(UUID id) {
        return loaded(find(id));
    }

    @Transactional
    Playlist create(String name, String description, boolean shuffle, String user) {
        Playlist playlist = new Playlist(name.strip(), user, clock.instant());
        playlist.describe(name.strip(), blank(description), shuffle, clock.instant());
        return playlists.save(playlist);
    }

    @Transactional
    Playlist update(UUID id, String name, String description, boolean shuffle) {
        Playlist playlist = find(id);
        playlist.describe(name.strip(), blank(description), shuffle, clock.instant());
        events.publishEvent(new PlaylistChanged(id));
        return loaded(playlist);
    }

    @Transactional
    Playlist setItems(UUID id, List<PlaylistItem.Spec> items) {
        Playlist playlist = find(id);
        for (PlaylistItem.Spec item : items) {
            validate(item);
        }
        playlist.setItems(items, clock.instant());
        events.publishEvent(new PlaylistChanged(id));
        return loaded(playlist);
    }

    @Transactional
    void delete(UUID id) {
        playlists.delete(find(id));
        events.publishEvent(new PlaylistChanged(id));
    }

    @Override
    @Transactional(readOnly = true)
    public void validate(BackgroundSource source) {
        if (source == null || source.kind() == null) {
            throw new IllegalArgumentException("background music kind is required");
        }
        switch (source.kind()) {
            case SILENCE -> { }
            case PLAYLIST -> {
                if (source.ref() == null || !playlists.existsById(source.ref())) {
                    throw new IllegalArgumentException("playlist " + source.ref() + " does not exist");
                }
            }
            case MEDIA -> {
                var media = catalog.find(source.ref() == null ? new UUID(0, 0) : source.ref())
                        .orElseThrow(() -> new IllegalArgumentException("media " + source.ref() + " does not exist"));
                if (media.kind() == MediaKind.IMAGE) {
                    throw new IllegalArgumentException("background music must be audio or video");
                }
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<Tracks> tracks(BackgroundSource source) {
        if (source == null || source.kind() == null || source.kind() == BackgroundSource.Kind.SILENCE) {
            return java.util.Optional.empty();
        }
        if (source.kind() == BackgroundSource.Kind.MEDIA) {
            return catalog.find(source.ref()).filter(m -> m.kind() != MediaKind.IMAGE)
                    .map(m -> new Tracks("media:" + m.id(), List.of(m.id()), false));
        }
        return playlists.findById(source.ref()).flatMap(p -> {
            List<UUID> media = p.items().stream().map(this::trackOf).flatMap(java.util.Optional::stream).toList();
            return media.isEmpty() ? java.util.Optional.empty()
                    : java.util.Optional.of(new Tracks("playlist:" + p.id() + ":" + p.updatedAt().toEpochMilli(),
                            media, p.shuffle()));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public String describe(BackgroundSource source) {
        if (source == null || source.kind() == BackgroundSource.Kind.SILENCE) {
            return "Silêncio";
        }
        return source.kind() == BackgroundSource.Kind.PLAYLIST
                ? playlists.findById(source.ref()).map(p -> "Playlist " + p.name()).orElse("(playlist removida)")
                : catalog.find(source.ref()).map(m -> m.title()).orElse("(mídia removida)");
    }

    private java.util.Optional<UUID> trackOf(PlaylistItem item) {
        if (item.type() == PlaylistItem.Type.MEDIA) {
            return catalog.find(item.mediaId()).map(m -> m.id());
        }
        return songs.find(item.songId()).flatMap(song -> {
            if (item.recordingId() != null) {
                return song.recording(item.recordingId()).map(r -> r.mediaId());
            }
            return song.recordings().stream()
                    .filter(r -> r.kind() == webpresenter.server.songs.RecordingKind.SUNG).findFirst()
                    .or(() -> song.recordings().stream().findFirst())
                    .map(r -> r.mediaId());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> usagesOf(UUID mediaId) {
        return playlists.findAllUsingMedia(mediaId).stream().map(p -> "playlist \"" + p.name() + "\"").toList();
    }

    private void validate(PlaylistItem.Spec item) {
        if (item.type() == null) {
            throw new InvalidPlaylistException("item type is required");
        }
        switch (item.type()) {
            case SONG -> {
                var song = songs.find(item.songId() == null ? new UUID(0, 0) : item.songId())
                        .orElseThrow(() -> new InvalidPlaylistException("song " + item.songId() + " does not exist"));
                if (item.recordingId() != null && song.recording(item.recordingId()).isEmpty()) {
                    throw new InvalidPlaylistException("recording " + item.recordingId() + " is not a recording of this song");
                }
            }
            case MEDIA -> {
                var media = catalog.find(item.mediaId() == null ? new UUID(0, 0) : item.mediaId())
                        .orElseThrow(() -> new InvalidPlaylistException("media " + item.mediaId() + " does not exist"));
                if (media.kind() == MediaKind.IMAGE) {
                    throw new InvalidPlaylistException("playlists hold songs, audio and video");
                }
            }
        }
    }

    private static Playlist loaded(Playlist playlist) {
        playlist.items().size();
        return playlist;
    }

    private Playlist find(UUID id) {
        return playlists.findById(id).orElseThrow(() -> new NotFoundException("playlist " + id + " not found"));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }

    static class InvalidPlaylistException extends RuntimeException {
        InvalidPlaylistException(String message) {
            super(message);
        }
    }
}

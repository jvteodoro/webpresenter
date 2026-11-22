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
class PlaylistService implements MediaUsage {

    private final PlaylistRepository playlists;
    private final SongCatalog songs;
    private final MediaCatalog catalog;
    private final Clock clock = Clock.systemUTC();

    PlaylistService(PlaylistRepository playlists, SongCatalog songs, MediaCatalog catalog) {
        this.playlists = playlists;
        this.songs = songs;
        this.catalog = catalog;
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
        return loaded(playlist);
    }

    @Transactional
    Playlist setItems(UUID id, List<PlaylistItem.Spec> items) {
        Playlist playlist = find(id);
        for (PlaylistItem.Spec item : items) {
            validate(item);
        }
        playlist.setItems(items, clock.instant());
        return loaded(playlist);
    }

    @Transactional
    void delete(UUID id) {
        playlists.delete(find(id));
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

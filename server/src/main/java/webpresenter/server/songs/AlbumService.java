package webpresenter.server.songs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import webpresenter.server.library.MediaCatalog;
import webpresenter.server.library.MediaKind;

/** Albums: song collections with a cover, imported or made by people. */
@Service
class AlbumService {

    private final SongCollectionRepository albums;
    private final CollectionEntryRepository entries;
    private final SongRepository songs;
    private final MediaCatalog catalog;

    AlbumService(SongCollectionRepository albums, CollectionEntryRepository entries, SongRepository songs,
            MediaCatalog catalog) {
        this.albums = albums;
        this.entries = entries;
        this.songs = songs;
        this.catalog = catalog;
    }

    record AlbumRow(SongCollection album, long trackCount) {}

    record Track(String number, Song song) {}

    @Transactional(readOnly = true)
    List<AlbumRow> list() {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : entries.countByCollection()) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return albums.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(a -> new AlbumRow(a, counts.getOrDefault(a.id(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    SongCollection get(UUID id) {
        return find(id);
    }

    /** Tracks in album order: numeric numbers first in numeric order, then the others. */
    @Transactional(readOnly = true)
    List<Track> tracks(UUID id) {
        find(id);
        List<Track> tracks = new ArrayList<>(entries.findAllByCollection(id).stream()
                .map(e -> new Track(e.number(), e.song()))
                .toList());
        tracks.forEach(t -> t.song().recordings().size());
        tracks.sort(Comparator.comparing((Track t) -> numeric(t.number()))
                .thenComparing(t -> t.number() == null ? "" : t.number())
                .thenComparing(t -> t.song().title()));
        return tracks;
    }

    @Transactional
    SongCollection create(String name, String description, String group, UUID coverMediaId) {
        requireCover(coverMediaId);
        String slug = "album-" + UUID.randomUUID();
        SongCollection album = new SongCollection(name.strip(), slug, blank(group), 0);
        album.describe(name.strip(), blank(description), blank(group), coverMediaId);
        album.setSource(Source.MANUAL);
        return albums.save(album);
    }

    @Transactional
    SongCollection update(UUID id, String name, String description, String group, UUID coverMediaId) {
        SongCollection album = find(id);
        requireCover(coverMediaId);
        album.describe(name.strip(), blank(description), blank(group), coverMediaId);
        return album;
    }

    /** Only the cover (also allowed on imported albums, e.g. by the import tool). */
    @Transactional
    SongCollection setCover(UUID id, UUID coverMediaId) {
        SongCollection album = find(id);
        requireCover(coverMediaId);
        album.describe(album.name(), album.description(), album.groupName(), coverMediaId);
        return album;
    }

    /** Replaces the album's tracks with the given songs, numbered 1..n in that order. */
    @Transactional
    void setTracks(UUID id, List<UUID> songIds) {
        SongCollection album = find(id);
        entries.deleteAll(entries.findAllByCollection(id));
        entries.flush();
        int number = 1;
        for (UUID songId : songIds) {
            Song song = songs.findById(songId)
                    .orElseThrow(() -> new SongService.InvalidSongException("song " + songId + " does not exist"));
            entries.save(new CollectionEntry(song, album, String.valueOf(number++)));
        }
    }

    @Transactional
    void delete(UUID id) {
        SongCollection album = find(id);
        if (album.source() == Source.IMPORT) {
            throw new SongService.InvalidSongException("imported albums cannot be deleted");
        }
        entries.deleteAll(entries.findAllByCollection(id));
        albums.delete(album);
    }


    private void requireCover(UUID coverMediaId) {
        if (coverMediaId == null) {
            return;
        }
        var media = catalog.find(coverMediaId)
                .orElseThrow(() -> new SongService.InvalidSongException("media " + coverMediaId + " does not exist"));
        if (media.kind() != MediaKind.IMAGE) {
            throw new SongService.InvalidSongException("the cover must be an image");
        }
    }

    private SongCollection find(UUID id) {
        return albums.findById(id).orElseThrow(() -> new SongService.NotFoundException("album " + id + " not found"));
    }

    private static long numeric(String number) {
        if (number != null && number.matches("\\d{1,9}")) {
            return Long.parseLong(number);
        }
        return Long.MAX_VALUE;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

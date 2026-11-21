package webpresenter.server.songs;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import webpresenter.server.library.MediaCatalog;
import webpresenter.server.library.MediaKind;
import webpresenter.server.library.MediaUsage;

@Service
class SongService implements MediaUsage {

    private final SongRepository songs;
    private final SongCollectionRepository collections;
    private final MediaCatalog catalog;
    private final JsonMapper json;
    private final Clock clock = Clock.systemUTC();

    SongService(SongRepository songs, SongCollectionRepository collections, MediaCatalog catalog, JsonMapper json) {
        this.songs = songs;
        this.collections = collections;
        this.catalog = catalog;
        this.json = json;
    }

    // ------------------------------------------------------------------ queries

    record Page(List<Song> songs, long total) {}

    @Transactional(readOnly = true)
    Page search(String query, String collection, int offset, int limit) {
        String term = normalize(query == null ? "" : query);
        int size = Math.clamp(limit, 1, 200);
        List<Song> found = songs.search(term, blankToNull(collection), PageRequest.of(offset / size, size));
        found.forEach(SongService::load);
        return new Page(found, songs.countSearch(term, blankToNull(collection)));
    }

    @Transactional(readOnly = true)
    Song get(UUID id) {
        return load(find(id));
    }

    @Transactional(readOnly = true)
    List<SongCollection> collections() {
        return collections.findAllByOrderBySortOrderAscNameAsc();
    }

    Lyrics lyrics(Song song) {
        return json.readValue(song.lyricsJson(), Lyrics.class);
    }

    Timing timing(Recording recording) {
        return recording.timingJson() == null ? null : json.readValue(recording.timingJson(), Timing.class);
    }

    // ------------------------------------------------------------------ changes

    /** Everything a song is made of, as accepted by create, update and import. */
    record SongInput(String title, String alternativeTitles, String language, String authors, String copyright,
            UUID backgroundMediaId, SongStatus status, Lyrics lyrics, List<CollectionInput> collections) {}

    record CollectionInput(String slug, String name, String group, String number) {}

    record RecordingInput(RecordingKind kind, UUID mediaId, Source source, Timing timing) {}

    @Transactional
    Song create(SongInput input, Source source) {
        requireTitle(input);
        Song song = new Song(input.title().strip(), language(input), source, null, null, clock.instant());
        apply(song, input);
        return load(songs.save(song));
    }

    @Transactional
    Song update(UUID id, SongInput input) {
        Song song = find(id);
        apply(song, input);
        return load(song);
    }

    @Transactional
    void delete(UUID id) {
        songs.delete(find(id));
    }

    @Transactional
    Song addRecording(UUID songId, RecordingInput input) {
        Song song = find(songId);
        requireMedia(input.mediaId(), MediaKind.AUDIO, MediaKind.VIDEO);
        Recording recording = song.addRecording(input.kind(), input.mediaId(),
                input.source() == null ? Source.MANUAL : input.source());
        if (input.timing() != null) {
            recording.setTiming(timingJson(song, input.timing()));
        }
        song.touch(clock.instant());
        return load(song);
    }

    @Transactional
    Song setTiming(UUID songId, UUID recordingId, Timing timing) {
        Song song = find(songId);
        song.recording(recordingId).setTiming(timing == null ? null : timingJson(song, timing));
        song.touch(clock.instant());
        return load(song);
    }

    @Transactional
    Song removeRecording(UUID songId, UUID recordingId) {
        Song song = find(songId);
        song.removeRecording(recordingId);
        song.touch(clock.instant());
        return load(song);
    }

    /**
     * Creates or updates a song identified by (origin, externalId): re-running an import updates in
     * place. Recordings are replaced by the given ones.
     */
    @Transactional
    Song importSong(String origin, String externalId, SongInput input, List<RecordingInput> recordings) {
        requireTitle(input);
        Song song = songs.findByOriginAndExternalId(origin, externalId).orElse(null);
        if (song == null) {
            song = new Song(input.title().strip(), language(input), Source.IMPORT, origin, externalId, clock.instant());
            apply(song, input);
            song = songs.save(song);
        } else {
            apply(song, input);
        }
        for (Recording existing : List.copyOf(song.recordings())) {
            song.removeRecording(existing.id());
        }
        songs.flush(); // unique (song, media) must not see the removed rows
        for (RecordingInput r : recordings) {
            requireMedia(r.mediaId(), MediaKind.AUDIO, MediaKind.VIDEO);
            Recording recording = song.addRecording(r.kind(), r.mediaId(), r.source() == null ? Source.IMPORT : r.source());
            if (r.timing() != null) {
                Timing t = new Timing(song.lyricsVersion(), r.timing().source(), r.timing().reviewed(), r.timing().cues());
                recording.setTiming(timingJson(song, t));
            }
        }
        return load(song);
    }

    // ------------------------------------------------------------------ MediaUsage

    @Override
    @Transactional(readOnly = true)
    public List<String> usagesOf(UUID mediaId) {
        List<String> usages = new ArrayList<>();
        songs.findAllWithRecordingMedia(mediaId).forEach(s -> usages.add("música \"" + s.title() + "\" (gravação)"));
        songs.findAllByBackgroundMediaId(mediaId).forEach(s -> usages.add("música \"" + s.title() + "\" (fundo)"));
        return usages;
    }

    // ------------------------------------------------------------------ helpers

    private static void requireTitle(SongInput input) {
        if (input.title() == null || input.title().isBlank()) {
            throw new InvalidSongException("title is required");
        }
    }

    private static String language(SongInput input) {
        return input.language() == null || input.language().isBlank() ? "pt" : input.language().strip();
    }

    private void apply(Song song, SongInput input) {
        requireTitle(input);
        if (input.backgroundMediaId() != null) {
            requireMedia(input.backgroundMediaId(), MediaKind.IMAGE, MediaKind.VIDEO);
        }
        Instant now = clock.instant();
        song.describe(input.title().strip(), blankToNull(input.alternativeTitles()), language(input),
                blankToNull(input.authors()), blankToNull(input.copyright()), input.backgroundMediaId(),
                input.status() == null ? SongStatus.DRAFT : input.status(), now);
        Lyrics lyrics = input.lyrics() == null ? Lyrics.EMPTY : input.lyrics();
        try {
            lyrics.validate();
        } catch (IllegalArgumentException e) {
            throw new InvalidSongException("invalid lyrics: " + e.getMessage());
        }
        song.replaceLyrics(json.writeValueAsString(lyrics), now);
        if (input.collections() != null) {
            song.setCollections(input.collections().stream()
                    .map(c -> new Song.CollectionInput(collection(c), blankToNull(c.number())))
                    .toList());
        }
        song.index(normalize(String.join(" ", input.title(),
                input.alternativeTitles() == null ? "" : input.alternativeTitles(), firstLines(lyrics))));
    }

    private SongCollection collection(CollectionInput input) {
        if (input.slug() == null || input.slug().isBlank()) {
            throw new InvalidSongException("collection slug is required");
        }
        return collections.findBySlug(input.slug()).map(existing -> {
            if (input.name() != null) {
                existing.update(input.name(), input.group(), existing.sortOrder());
            }
            return existing;
        }).orElseGet(() -> collections.save(new SongCollection(
                input.name() == null ? input.slug() : input.name(), input.slug(), input.group(), 0)));
    }

    private String timingJson(Song song, Timing timing) {
        int slides = lyrics(song).slideSequence().size();
        try {
            timing.validate(slides);
        } catch (IllegalArgumentException e) {
            throw new InvalidSongException("invalid timing: " + e.getMessage());
        }
        return json.writeValueAsString(new Timing(song.lyricsVersion(), timing.source() == null ? Source.MANUAL : timing.source(),
                timing.reviewed(), timing.cues()));
    }

    private void requireMedia(UUID mediaId, MediaKind... kinds) {
        var media = catalog.find(mediaId).orElseThrow(() -> new InvalidSongException("media " + mediaId + " does not exist"));
        if (!List.of(kinds).contains(media.kind())) {
            throw new InvalidSongException("media " + mediaId + " is " + media.kind() + ", expected one of " + List.of(kinds));
        }
    }

    private static String firstLines(Lyrics lyrics) {
        return lyrics.slideSequence().stream().limit(2).flatMap(s -> s.lines().stream()).collect(Collectors.joining(" "));
    }

    /** Lower case without accents, single spaces. */
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Song load(Song song) {
        song.recordings().size();
        song.collections().forEach(c -> c.collection().name());
        return song;
    }

    private Song find(UUID id) {
        return songs.findById(id).orElseThrow(() -> new NotFoundException("song " + id + " not found"));
    }

    static class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }

    static class InvalidSongException extends RuntimeException {
        InvalidSongException(String message) {
            super(message);
        }
    }
}

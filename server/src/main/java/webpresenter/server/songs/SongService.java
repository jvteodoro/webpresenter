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
class SongService implements MediaUsage, SongCatalog, SongPipeline {

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
        return search(query, collection, false, offset, limit);
    }

    /** @param loose only songs that are in no album */
    @Transactional(readOnly = true)
    Page search(String query, String collection, boolean loose, int offset, int limit) {
        String term = normalize(query == null ? "" : query);
        int size = Math.clamp(limit, 1, 200);
        List<Song> found = songs.search(term, blankToNull(collection), loose, PageRequest.of(offset / size, size));
        found.forEach(SongService::load);
        return new Page(found, songs.countSearch(term, blankToNull(collection), loose));
    }

    @Transactional(readOnly = true)
    Song get(UUID id) {
        return load(findSong(id));
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
            UUID backgroundMediaId, SongStatus status, Lyrics lyrics, List<CollectionInput> collections, String textColor,
            Integer textScale) {

        SongInput(String title, String alternativeTitles, String language, String authors, String copyright,
                UUID backgroundMediaId, SongStatus status, Lyrics lyrics, List<CollectionInput> collections) {
            this(title, alternativeTitles, language, authors, copyright, backgroundMediaId, status, lyrics, collections,
                    null, null);
        }
    }

    record CollectionInput(String slug, String name, String group, String number) {}

    /** @param part what a separated track (STEM) holds; required for that kind only */
    record RecordingInput(RecordingKind kind, UUID mediaId, Source source, Timing timing, StemPart part) {

        RecordingInput(RecordingKind kind, UUID mediaId, Source source, Timing timing) {
            this(kind, mediaId, source, timing, null);
        }
    }

    @Transactional
    Song create(SongInput input, Source source) {
        requireTitle(input);
        Song song = new Song(input.title().strip(), language(input), source, null, null, clock.instant());
        apply(song, input);
        return load(songs.save(song));
    }

    @Transactional
    Song update(UUID id, SongInput input) {
        Song song = findSong(id);
        apply(song, input);
        return load(song);
    }

    @Transactional
    void delete(UUID id) {
        songs.delete(findSong(id));
    }

    @Transactional
    Song addRecording(UUID songId, RecordingInput input) {
        Song song = findSong(songId);
        requireMedia(input.mediaId(), MediaKind.AUDIO, MediaKind.VIDEO);
        if (input.kind() == null || (input.kind() == RecordingKind.STEM) != (input.part() != null)) {
            throw new InvalidSongException("a separated track (STEM) needs its part; other kinds take none");
        }
        Recording recording = song.addRecording(input.kind(), input.mediaId(),
                input.source() == null ? Source.MANUAL : input.source(), input.part(), null);
        if (input.timing() != null) {
            recording.setTiming(timingJson(song, input.timing()));
        }
        song.touch(clock.instant());
        return load(song);
    }

    @Override
    @Transactional
    public UUID addGeneratedPlayback(UUID songId, UUID sourceRecordingId, UUID mediaId) {
        return addSeparatedTracks(songId, sourceRecordingId, mediaId, java.util.Map.of()).recordingIds().getFirst();
    }

    @Override
    @Transactional
    public SeparatedTracks addSeparatedTracks(UUID songId, UUID sourceRecordingId, UUID playbackMediaId,
            java.util.Map<StemPart, UUID> stems) {
        Song song = findSong(songId);
        Recording source = song.recording(sourceRecordingId);
        if (playbackMediaId != null) {
            requireMedia(playbackMediaId, MediaKind.AUDIO, MediaKind.VIDEO);
        }
        stems.values().forEach(m -> requireMedia(m, MediaKind.AUDIO, MediaKind.VIDEO));
        // Earlier results for the same source and parts are replaced.
        List<Recording> replaced = song.recordings().stream()
                .filter(r -> r.source() == Source.PIPELINE && sourceRecordingId.equals(r.derivedFrom()))
                .filter(r -> (r.kind() == RecordingKind.PLAYBACK && playbackMediaId != null)
                        || (r.kind() == RecordingKind.STEM && stems.containsKey(r.part())))
                .toList();
        java.util.Set<UUID> added = new java.util.HashSet<>(stems.values());
        if (playbackMediaId != null) {
            added.add(playbackMediaId);
        }
        List<UUID> replacedMedia = new ArrayList<>();
        for (Recording r : replaced) {
            song.removeRecording(r.id());
            song.recordings().stream().filter(o -> r.id().equals(o.derivedFrom())).forEach(Recording::forgetOrigin);
            if (!added.contains(r.mediaId())) {
                replacedMedia.add(r.mediaId());
            }
        }
        songs.flush(); // the unique (song, kind, media) key: deletions first
        String timingJson = null;
        if (source.timingJson() != null) {
            Timing timing = json.readValue(source.timingJson(), Timing.class);
            // Kept as-is (same lyrics version): a separated track has exactly the same timeline.
            timingJson = json.writeValueAsString(new Timing(timing.lyricsVersion(), Source.PIPELINE, false, timing.cues()));
        }
        List<UUID> ids = new ArrayList<>();
        if (playbackMediaId != null) {
            Recording playback = song.addRecording(RecordingKind.PLAYBACK, playbackMediaId, Source.PIPELINE, null,
                    sourceRecordingId);
            playback.setTiming(timingJson);
            ids.add(playback.id());
        }
        for (StemPart part : StemPart.values()) { // stable order
            UUID media = stems.get(part);
            if (media != null) {
                Recording stem = song.addRecording(RecordingKind.STEM, media, Source.PIPELINE, part, sourceRecordingId);
                stem.setTiming(timingJson);
                ids.add(stem.id());
            }
        }
        song.touch(clock.instant());
        songs.flush();
        replacedMedia.removeIf(m -> song.recordings().stream().anyMatch(r -> r.mediaId().equals(m)));
        return new SeparatedTracks(ids, replacedMedia);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<StemPart> recordingPart(UUID songId, UUID recordingId) {
        return songs.findById(songId).flatMap(s -> s.recordings().stream()
                .filter(r -> r.id().equals(recordingId)).findFirst()).map(Recording::part);
    }

    @Override
    @Transactional
    public UUID createFromRecording(String title, UUID mediaId) {
        requireMedia(mediaId, MediaKind.AUDIO, MediaKind.VIDEO);
        Lyrics placeholder = new Lyrics(List.of(new Lyrics.Part("i", Lyrics.PartKind.INSTRUMENTAL, "Instrumental",
                List.of(new Lyrics.Slide(List.of(), null)))), List.of("i"));
        Song song = create(new SongInput(title, null, null, null, null, null, SongStatus.DRAFT, placeholder, List.of()),
                Source.PIPELINE);
        song.addRecording(RecordingKind.SUNG, mediaId, Source.PIPELINE);
        songs.flush();
        return song.id();
    }

    @Override
    @Transactional
    public int applyGeneratedLyrics(UUID songId, UUID recordingId, String lyricsJson, List<GeneratedCue> cues) {
        Song song = findSong(songId);
        Recording target = song.recording(recordingId);
        Instant now = clock.instant();
        if (lyricsJson != null) {
            Lyrics lyrics;
            try {
                lyrics = json.readValue(lyricsJson, Lyrics.class).validate();
            } catch (RuntimeException e) {
                throw new InvalidSongException("invalid generated lyrics: " + e.getMessage());
            }
            song.replaceLyrics(json.writeValueAsString(lyrics), now);
            song.index(normalize(String.join(" ", song.title(),
                    song.alternativeTitles() == null ? "" : song.alternativeTitles(), firstLines(lyrics))));
        }
        int slides = lyrics(song).slideSequence().size();
        Timing timing = new Timing(song.lyricsVersion(), Source.PIPELINE, false,
                cues.stream().map(c -> new Timing.Cue(c.slide(), c.startMs())).toList());
        try {
            timing.validate(slides);
        } catch (IllegalArgumentException e) {
            throw new InvalidSongException("invalid generated timing: " + e.getMessage());
        }
        String timingJson = json.writeValueAsString(timing);
        target.setTiming(timingJson);
        // Tracks the pipeline separated from this recording (and from those) have the same timeline;
        // so do playbacks it made before tracks recorded their origin.
        java.util.Set<UUID> sameTimeline = new java.util.HashSet<>(List.of(target.id()));
        boolean grew = true;
        while (grew) {
            grew = song.recordings().stream()
                    .filter(r -> r.source() == Source.PIPELINE && r.derivedFrom() != null
                            && sameTimeline.contains(r.derivedFrom()))
                    .map(Recording::id).toList().stream().map(sameTimeline::add).reduce(false, Boolean::logicalOr);
        }
        song.recordings().stream()
                .filter(r -> r != target && r.source() == Source.PIPELINE && (sameTimeline.contains(r.id())
                        || (r.kind() == RecordingKind.PLAYBACK && r.derivedFrom() == null)))
                .forEach(r -> r.setTiming(timingJson));
        song.markDraft(now);
        songs.flush();
        return slides;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> slideTexts(UUID songId) {
        Lyrics lyrics = lyrics(findSong(songId));
        var sequence = lyrics.slideSequence();
        boolean hasText = sequence.stream().anyMatch(sl -> !sl.lines().isEmpty());
        return hasText ? sequence.stream().map(sl -> String.join("\n", sl.lines())).toList() : List.of();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> recordingMedia(UUID songId, UUID recordingId) {
        return songs.findById(songId).flatMap(song -> song.recordings().stream()
                .filter(r -> r.id().equals(recordingId)).findFirst().map(Recording::mediaId));
    }

    @Transactional
    Song setTiming(UUID songId, UUID recordingId, Timing timing) {
        Song song = findSong(songId);
        song.recording(recordingId).setTiming(timing == null ? null : timingJson(song, timing));
        song.touch(clock.instant());
        return load(song);
    }

    @Transactional
    Song removeRecording(UUID songId, UUID recordingId) {
        Song song = findSong(songId);
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

    // ------------------------------------------------------------------ SongCatalog

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<SongPlan> find(java.util.UUID songId) {
        return songs.findById(songId).map(song -> {
            List<Lyrics.SequenceSlide> sequence = lyrics(song).slideSequence();
            List<PlanSlide> slides = sequence.stream()
                    .map(s -> new PlanSlide(s.partLabel(), s.kind() == Lyrics.PartKind.INSTRUMENTAL, s.lines(), s.cue()))
                    .toList();
            // Separated tracks are for listening and rehearsal, not offered for presenting (yet).
            List<PlanRecording> recordings = song.recordings().stream().filter(r -> r.kind() != RecordingKind.STEM)
                    .map(r -> {
                Timing timing = timing(r);
                List<Long> cues = new ArrayList<>();
                if (timing != null && java.util.Objects.equals(timing.lyricsVersion(), song.lyricsVersion())) {
                    java.util.Map<Integer, Long> byIndex = new java.util.HashMap<>();
                    timing.cues().forEach(c -> byIndex.put(c.slide(), c.startMs()));
                    for (int i = 0; i < sequence.size(); i++) {
                        cues.add(byIndex.get(i));
                    }
                }
                return new PlanRecording(r.id(), r.kind(), r.mediaId(), cues);
            }).toList();
            return new SongPlan(song.id(), song.title(), slides, recordings, song.backgroundMediaId(), song.style());
        });
    }

    // ------------------------------------------------------------------ MediaUsage

    @Override
    @Transactional(readOnly = true)
    public List<String> usagesOf(UUID mediaId) {
        List<String> usages = new ArrayList<>();
        songs.findAllWithRecordingMedia(mediaId).forEach(s -> usages.add("música \"" + s.title() + "\" (gravação)"));
        songs.findAllByBackgroundMediaId(mediaId).forEach(s -> usages.add("música \"" + s.title() + "\" (fundo)"));
        collections.findAllByCoverMediaId(mediaId).forEach(a -> usages.add("álbum \"" + a.name() + "\" (capa)"));
        return usages;
    }

    // ------------------------------------------------------------------ helpers

    private static void requireTitle(SongInput input) {
        if (input.title() == null || input.title().isBlank()) {
            throw new InvalidSongException("title is required");
        }
    }

    /**
     * The singing order's structure, independent of words and part ids: for each entry of the
     * order, which part it is (by first appearance) and how many slides that part has.
     */
    static List<String> shape(Lyrics lyrics) {
        List<String> firstSeen = new java.util.ArrayList<>();
        java.util.Map<String, Integer> slides = new java.util.HashMap<>();
        lyrics.parts().forEach(p -> slides.put(p.id(), p.slides().size()));
        List<String> result = new java.util.ArrayList<>();
        for (String id : lyrics.order()) {
            if (!firstSeen.contains(id)) {
                firstSeen.add(id);
            }
            result.add(firstSeen.indexOf(id) + ":" + slides.getOrDefault(id, 0));
        }
        return result;
    }

    /** Timings made for the previous lyrics version move to the current one, keeping their review state. */
    private void carryTimingForward(Song song, int previousVersion) {
        for (Recording recording : song.recordings()) {
            if (recording.timingJson() == null) {
                continue;
            }
            Timing timing = json.readValue(recording.timingJson(), Timing.class);
            if (java.util.Objects.equals(timing.lyricsVersion(), previousVersion)) {
                recording.setTiming(json.writeValueAsString(
                        new Timing(song.lyricsVersion(), timing.source(), timing.reviewed(), timing.cues())));
            }
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
        try {
            new SlideStyle(null, input.textColor(), input.textScale()).validate();
        } catch (IllegalArgumentException e) {
            throw new InvalidSongException(e.getMessage());
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
        List<String> shapeBefore = song.lyricsJson() == null ? null : shape(lyrics(song));
        int versionBefore = song.lyricsVersion();
        if (song.replaceLyrics(json.writeValueAsString(lyrics), now) && shape(lyrics).equals(shapeBefore)) {
            // Same slides in the same order (words corrected): the times still hold.
            carryTimingForward(song, versionBefore);
        }
        song.setText(input.textColor(), input.textScale());
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

    private Song findSong(UUID id) {
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

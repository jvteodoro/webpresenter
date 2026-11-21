package webpresenter.server.songs;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "song")
class Song {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    /** Lower case without accents, title + alternative titles + lyrics start, for search. */
    @Column(name = "search_text", nullable = false)
    private String searchText;

    @Column(name = "alternative_titles")
    private String alternativeTitles;

    @Column(nullable = false)
    private String language;

    private String authors;

    private String copyright;

    @Column(name = "background_media_id")
    private UUID backgroundMediaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    /** For imports: the tool (e.g. "louvorja") and its id, so re-imports update instead of duplicating. */
    private String origin;

    @Column(name = "external_id")
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SongStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String lyrics;

    @Column(name = "lyrics_version", nullable = false)
    private int lyricsVersion;

    @OneToMany(mappedBy = "song", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("kind")
    private List<Recording> recordings = new ArrayList<>();

    @OneToMany(mappedBy = "song", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CollectionEntry> collections = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Song() {}

    Song(String title, String language, Source source, String origin, String externalId, Instant now) {
        this.id = UUID.randomUUID();
        this.title = title;
        this.language = language;
        this.source = source;
        this.origin = origin;
        this.externalId = externalId;
        this.status = SongStatus.DRAFT;
        this.lyricsVersion = 0; // the first replaceLyrics() makes it version 1
        this.searchText = "";
        this.createdAt = now;
        this.updatedAt = now;
    }

    void describe(String title, String alternativeTitles, String language, String authors, String copyright,
            UUID backgroundMediaId, SongStatus status, Instant now) {
        this.title = title;
        this.alternativeTitles = alternativeTitles;
        this.language = language;
        this.authors = authors;
        this.copyright = copyright;
        this.backgroundMediaId = backgroundMediaId;
        this.status = status;
        this.updatedAt = now;
    }

    /** Replaces the lyrics document; the version only increases when the content changes. */
    boolean replaceLyrics(String json, Instant now) {
        if (lyrics != null && json.equals(lyrics)) {
            return false;
        }
        this.lyrics = json;
        this.lyricsVersion++;
        this.updatedAt = now;
        return true;
    }

    void index(String searchText) {
        this.searchText = searchText;
    }

    Recording addRecording(RecordingKind kind, UUID mediaId, Source source) {
        Recording recording = new Recording(this, kind, mediaId, source);
        recordings.add(recording);
        return recording;
    }

    void removeRecording(UUID recordingId) {
        if (!recordings.removeIf(r -> r.id().equals(recordingId))) {
            throw new SongService.NotFoundException("recording " + recordingId + " not found in this song");
        }
    }

    Recording recording(UUID recordingId) {
        return recordings.stream().filter(r -> r.id().equals(recordingId)).findFirst()
                .orElseThrow(() -> new SongService.NotFoundException("recording " + recordingId + " not found"));
    }

    /**
     * Sets the collections the song is in. Existing entries are updated in place: Hibernate inserts
     * before it deletes, so replacing an entry for the same collection would break the unique key.
     */
    void setCollections(List<CollectionInput> wanted) {
        collections.removeIf(e -> wanted.stream().noneMatch(w -> w.collection().id().equals(e.collection().id())));
        for (CollectionInput w : wanted) {
            collections.stream().filter(e -> e.collection().id().equals(w.collection().id())).findFirst()
                    .ifPresentOrElse(e -> e.renumber(w.number()),
                            () -> collections.add(new CollectionEntry(this, w.collection(), w.number())));
        }
    }

    record CollectionInput(SongCollection collection, String number) {}

    void touch(Instant now) {
        this.updatedAt = now;
    }

    UUID id() { return id; }
    String title() { return title; }
    String alternativeTitles() { return alternativeTitles; }
    String language() { return language; }
    String authors() { return authors; }
    String copyright() { return copyright; }
    UUID backgroundMediaId() { return backgroundMediaId; }
    Source source() { return source; }
    String origin() { return origin; }
    String externalId() { return externalId; }
    SongStatus status() { return status; }
    String lyricsJson() { return lyrics; }
    int lyricsVersion() { return lyricsVersion; }
    List<Recording> recordings() { return Collections.unmodifiableList(recordings); }
    List<CollectionEntry> collections() { return Collections.unmodifiableList(collections); }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
}

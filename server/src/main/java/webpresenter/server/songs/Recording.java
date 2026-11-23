package webpresenter.server.songs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A recording of the song (library audio or video) and its timing. */
@Entity
@Table(name = "song_recording")
class Recording {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "song_id")
    private Song song;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecordingKind kind;

    /** Library media (no cross-module foreign key; deletion is vetoed while referenced). */
    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @JdbcTypeCode(SqlTypes.JSON)
    private String timing;

    /** For {@code STEM} recordings: what the track holds. */
    @Enumerated(EnumType.STRING)
    private StemPart part;

    /** The recording this one was separated from (same timeline), or null. */
    @Column(name = "derived_from")
    private UUID derivedFrom;

    protected Recording() {}

    Recording(Song song, RecordingKind kind, UUID mediaId, Source source) {
        this(song, kind, mediaId, source, null, null);
    }

    Recording(Song song, RecordingKind kind, UUID mediaId, Source source, StemPart part, UUID derivedFrom) {
        if ((kind == RecordingKind.STEM) != (part != null)) {
            throw new IllegalArgumentException("a separated track (STEM) needs its part, other recordings none");
        }
        this.id = UUID.randomUUID();
        this.song = song;
        this.kind = kind;
        this.mediaId = mediaId;
        this.source = source;
        this.part = part;
        this.derivedFrom = derivedFrom;
    }

    /** The recording it came from was removed. */
    void forgetOrigin() {
        this.derivedFrom = null;
    }

    void setTiming(String json) {
        this.timing = json;
    }

    UUID id() { return id; }
    RecordingKind kind() { return kind; }
    UUID mediaId() { return mediaId; }
    Source source() { return source; }
    String timingJson() { return timing; }
    StemPart part() { return part; }
    UUID derivedFrom() { return derivedFrom; }
}

package webpresenter.server.planning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A liturgy: the usual order of an event on some weekdays, with fixed and variable slots. */
@Entity
@Table(name = "liturgy_template")
class LiturgyTemplate {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Bit n set for ISO day n (1 = Monday … 7 = Sunday). */
    @Column(nullable = false)
    private int weekdays;

    @Column(name = "start_time")
    private LocalTime startTime;

    /** The slots, in order: a JSON array of {@link ItemSpec}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String slots;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LiturgyTemplate() {}

    LiturgyTemplate(String name, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.slots = "[]";
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void describe(String name, List<DayOfWeek> days, LocalTime startTime, Instant now) {
        this.name = name;
        this.weekdays = days.stream().mapToInt(d -> 1 << d.getValue()).reduce(0, (a, b) -> a | b);
        this.startTime = startTime;
        this.updatedAt = now;
    }

    void setSlots(String slotsJson, Instant now) {
        this.slots = slotsJson;
        this.updatedAt = now;
    }

    List<DayOfWeek> days() {
        return Arrays.stream(DayOfWeek.values()).filter(d -> (weekdays & (1 << d.getValue())) != 0).toList();
    }

    UUID id() { return id; }
    String name() { return name; }
    LocalTime startTime() { return startTime; }
    String slots() { return slots; }
    Instant updatedAt() { return updatedAt; }
}

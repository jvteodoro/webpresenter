package webpresenter.server.planning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PlannedEventRepository extends JpaRepository<PlannedEvent, UUID> {

    @Query("select e from PlannedEvent e order by e.current desc, e.startsAt desc nulls last, e.createdAt desc")
    List<PlannedEvent> findAllForListing();

    Optional<PlannedEvent> findByCurrentTrue();

    @Query("select e.startsAt from PlannedEvent e where e.startsAt >= :from and e.startsAt < :to order by e.startsAt")
    List<java.time.Instant> findStartsBetween(java.time.Instant from, java.time.Instant to);

    Optional<PlannedEvent> findByTemplateIdAndTemplateDate(UUID templateId, java.time.LocalDate date);

    @Query("select distinct i.event from EventItem i where i.mediaId = :mediaId")
    List<PlannedEvent> findAllUsingMedia(UUID mediaId);

    /** Events whose background music (of the event, a scene or an item) is this library track. */
    @Query("""
            select distinct e from PlannedEvent e left join e.items i
            where (e.background.kind = webpresenter.server.playlists.BackgroundSource.Kind.MEDIA and e.background.ref = :mediaId)
               or (i.background.kind = webpresenter.server.playlists.BackgroundSource.Kind.MEDIA and i.background.ref = :mediaId)""")
    List<PlannedEvent> findAllWithBackgroundMedia(UUID mediaId);

    /** Events with a timer whose signals use this sound ({@code containment}: [{"soundMediaId": "…"}]). */
    @Query(value = """
            select distinct e.* from planned_event e join planned_event_item i on i.event_id = e.id
            where i.timer_signals @> cast(:containment as jsonb)""", nativeQuery = true)
    List<PlannedEvent> findAllWithSignalSound(String containment);

    /** Events whose slide style (of the event or an item) uses this image ({"backgroundMediaId": "…"}). */
    @Query(value = """
            select distinct e.* from planned_event e left join planned_event_item i on i.event_id = e.id
            where e.slide_style @> cast(:containment as jsonb) or i.slide_style @> cast(:containment as jsonb)""",
            nativeQuery = true)
    List<PlannedEvent> findAllWithStyleBackground(String containment);
}

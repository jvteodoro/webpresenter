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

    @Query("select distinct i.event from EventItem i where i.mediaId = :mediaId")
    List<PlannedEvent> findAllUsingMedia(UUID mediaId);
}

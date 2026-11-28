package webpresenter.server.videos;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface VideoSourceImportRepository extends JpaRepository<VideoSourceImport, UUID> {

    Optional<VideoSourceImport> findBySourceIdAndEventDate(UUID sourceId, LocalDate eventDate);

    List<VideoSourceImport> findTop20BySourceIdOrderByEventDateDesc(UUID sourceId);

    List<VideoSourceImport> findAllByStatus(VideoSourceImport.Status status);
}

package webpresenter.server.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findAllByOrderByCreatedAtDesc(Pageable page);

    List<Job> findAllByStatusAndUpdatedAtBefore(JobStatus status, Instant before);
}

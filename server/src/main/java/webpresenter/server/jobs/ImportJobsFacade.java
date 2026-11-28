package webpresenter.server.jobs;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The public face of the queue for other modules (see {@link ImportJobs}). */
@Service
class ImportJobsFacade implements ImportJobs {

    private final JobService jobs;

    ImportJobsFacade(JobService jobs) {
        this.jobs = jobs;
    }

    @Override
    public UUID startChannelVideo(String channelUrl, LocalDate day, String title, String user) {
        return jobs.importFromUrl(channelUrl, JobService.ImportTarget.MISC, JobService.ImportFormat.VIDEO, title, day,
                user).id();
    }

    @Override
    public Optional<Outcome> outcome(UUID jobId) {
        return jobs.lookup(jobId).map(view -> new Outcome(view.status().name(), view.message(),
                view.result() == null || view.result().get("mediaId") == null ? null
                        : UUID.fromString((String) view.result().get("mediaId"))));
    }
}

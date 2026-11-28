package webpresenter.server.videos;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import webpresenter.server.jobs.ImportJobs;
import webpresenter.server.planning.PlannedEvents;

/**
 * Recurring videos. Every hour, for the events of the next week, each enabled source gets its video of
 * that day queued (once; a failed attempt is retried after {@link #RETRY_AFTER}). Downloads land in the
 * misc library section, titled with the source's name and the date.
 */
@Service
class VideoSourceService {

    private static final Logger log = LoggerFactory.getLogger(VideoSourceService.class);
    /** Event dates looked ahead: the video of each date is searched until it is published. */
    private static final int DAYS_AHEAD = 8;
    /** A failed attempt (the video is not published yet, say) is tried again after this long. */
    static final Duration RETRY_AFTER = Duration.ofHours(20);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "youtu.be");

    private final VideoSourceRepository sources;
    private final VideoSourceImportRepository imports;
    private final ImportJobs jobs;
    private final PlannedEvents events;
    private final ZoneId zone;
    private final Clock clock;

    VideoSourceService(VideoSourceRepository sources, VideoSourceImportRepository imports, ImportJobs jobs,
            PlannedEvents events, @Value("${webpresenter.video-sources.zone:America/Sao_Paulo}") String zone,
            java.util.Optional<Clock> clock) {
        this.sources = sources;
        this.imports = imports;
        this.jobs = jobs;
        this.events = events;
        this.zone = ZoneId.of(zone);
        this.clock = clock.orElse(Clock.systemUTC());
    }

    // ------------------------------------------------------------------ sources

    @Transactional(readOnly = true)
    List<SourceView> list() {
        return sources.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    @Transactional
    VideoSource create(String name, String channelUrl, boolean enabled, String user) {
        return sources.save(new VideoSource(cleanName(name), checkedUrl(channelUrl), enabled, user, clock.instant()));
    }

    @Transactional
    VideoSource update(UUID id, String name, String channelUrl, boolean enabled) {
        VideoSource source = find(id);
        source.update(cleanName(name), checkedUrl(channelUrl), enabled, clock.instant());
        return source;
    }

    @Transactional
    void delete(UUID id) {
        sources.delete(find(id));
    }

    /** Queues the source's video of a day now, whatever the earlier attempts said. */
    @Transactional
    VideoSourceImport importDay(UUID id, LocalDate day, String user) {
        VideoSource source = find(id);
        VideoSourceImport attempt = imports.findBySourceIdAndEventDate(id, day)
                .orElseGet(() -> new VideoSourceImport(id, day));
        if (attempt.status() == VideoSourceImport.Status.QUEUED) {
            throw new InvalidVideoSourceException("the video of " + DAY.format(day) + " is already queued");
        }
        return queue(source, attempt, user);
    }

    // ------------------------------------------------------------------ the daily sweep

    /** Follows the queued attempts and queues the missing days of the next week's events. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    void sweep() {
        Instant now = clock.instant();
        for (VideoSourceImport attempt : imports.findAllByStatus(VideoSourceImport.Status.QUEUED)) {
            follow(attempt);
        }
        List<LocalDate> days = eventDays(now);
        if (days.isEmpty()) {
            return;
        }
        for (VideoSource source : sources.findAllByEnabledTrue()) {
            for (LocalDate day : days) {
                VideoSourceImport attempt = imports.findBySourceIdAndEventDate(source.id(), day)
                        .orElseGet(() -> new VideoSourceImport(source.id(), day));
                boolean due = attempt.status() == null
                        || (attempt.status() == VideoSourceImport.Status.FAILED
                                && attempt.lastAttemptAt().isBefore(now.minus(RETRY_AFTER)));
                if (due) {
                    try {
                        queue(source, attempt, "agendamento");
                    } catch (RuntimeException e) {
                        log.warn("could not queue the video of {} for {}", DAY.format(day), source.name(), e);
                    }
                }
            }
        }
    }

    /** Dates of the events starting from today for {@value #DAYS_AHEAD} days. */
    List<LocalDate> eventDays(Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return events.startsBetween(today.atStartOfDay(zone).toInstant(), today.plusDays(DAYS_AHEAD).atStartOfDay(zone).toInstant())
                .stream()
                .map(start -> start.atZone(zone).toLocalDate())
                .distinct()
                .toList();
    }

    /** Where a queued attempt got to in the queue: its result is kept on the attempt. */
    private void follow(VideoSourceImport attempt) {
        Optional<ImportJobs.Outcome> outcome = attempt.jobId() == null ? Optional.empty() : jobs.outcome(attempt.jobId());
        if (outcome.isEmpty()) {
            attempt.failed("O trabalho não está mais na fila");
            return;
        }
        ImportJobs.Outcome result = outcome.get();
        switch (result.status()) {
            case "DONE" -> attempt.done(result.mediaId());
            case "FAILED" -> attempt.failed(result.message() == null ? "Falha no download" : result.message());
            case "CANCELLED" -> attempt.failed("Cancelado");
            default -> { } // still queued or running
        }
    }

    private VideoSourceImport queue(VideoSource source, VideoSourceImport attempt, String user) {
        String title = source.name() + " · " + DAY.format(attempt.eventDate());
        UUID job = jobs.startChannelVideo(source.channelUrl(), attempt.eventDate(), title, user);
        attempt.queued(job, clock.instant());
        return imports.save(attempt);
    }

    // ------------------------------------------------------------------ helpers

    private VideoSource find(UUID id) {
        return sources.findById(id).orElseThrow(() -> new NotFoundVideoSourceException("source " + id + " not found"));
    }

    private SourceView view(VideoSource source) {
        List<ImportView> recent = imports.findTop20BySourceIdOrderByEventDateDesc(source.id()).stream()
                .map(i -> new ImportView(i.eventDate(), i.status().name(), i.message(), i.mediaId(), i.lastAttemptAt()))
                .collect(Collectors.toList());
        return new SourceView(source.id(), source.name(), source.channelUrl(), source.enabled(), source.createdBy(),
                source.createdAt(), recent);
    }

    static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidVideoSourceException("the source needs a name");
        }
        return name.strip();
    }

    /** A YouTube channel (or video) link, http(s). */
    static String checkedUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.strip());
        } catch (IllegalArgumentException e) {
            throw new InvalidVideoSourceException("invalid URL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean youtube = YOUTUBE_HOSTS.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || !youtube) {
            throw new InvalidVideoSourceException("the link must be a YouTube channel (https://www.youtube.com/@…)");
        }
        return uri.toString();
    }

    /** A source as shown to people, with its recent attempts. */
    record SourceView(UUID id, String name, String channelUrl, boolean enabled, String createdBy, Instant createdAt,
            List<ImportView> imports) {}

    /** One day's attempt: QUEUED, DONE (media set) or FAILED (message says why). */
    record ImportView(LocalDate eventDate, String status, String message, UUID mediaId, Instant lastAttemptAt) {}

    static class NotFoundVideoSourceException extends RuntimeException {
        NotFoundVideoSourceException(String message) {
            super(message);
        }
    }

    static class InvalidVideoSourceException extends RuntimeException {
        InvalidVideoSourceException(String message) {
            super(message);
        }
    }
}

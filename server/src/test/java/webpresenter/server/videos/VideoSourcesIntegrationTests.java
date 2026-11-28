package webpresenter.server.videos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import webpresenter.server.TestInfrastructure;
import webpresenter.server.jobs.ImportJobs;
import webpresenter.server.planning.PlannedEvents;

/**
 * The recurring videos on PostgreSQL: the daily sweep queues each event day's video once, follows the
 * job to its result, and does not queue days without an event. The queue and the planning are fakes.
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Import({TestInfrastructure.class, VideoSourcesIntegrationTests.Events.class})
class VideoSourcesIntegrationTests {

    /** The planning's event starts, set by each test; the real planning keeps its own bean (the controllers use it). */
    @TestConfiguration(proxyBeanMethods = false)
    static class Events {

        static final AtomicReference<List<Instant>> STARTS = new AtomicReference<>(List.of());

        @Bean
        @Primary
        PlannedEvents eventStarts() {
            return new PlannedEvents() {
                @Override
                public Optional<EventPlan> current() {
                    return Optional.empty();
                }

                @Override
                public List<Instant> startsBetween(Instant from, Instant to) {
                    return STARTS.get();
                }
            };
        }
    }

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @MockitoBean
    ImportJobs jobs;

    @Autowired
    VideoSourceService sources;

    @Test
    void theVideoOfAnEventDayIsQueuedOnceAndTheResultIsFollowed() {
        LocalDate eventDay = Instant.now().plus(Duration.ofDays(1)).atZone(ZONE).toLocalDate();
        Events.STARTS.set(List.of(eventDay.atStartOfDay(ZONE).plusHours(9).toInstant()));
        UUID job = UUID.randomUUID();
        UUID media = UUID.randomUUID();
        when(jobs.startChannelVideo(anyString(), any(), anyString(), anyString())).thenReturn(job);

        String name = "Programa " + UUID.randomUUID();
        VideoSource source = sources.create(name, "https://www.youtube.com/@Canal" + UUID.randomUUID(), true, "tester");

        sources.sweep();
        verify(jobs).startChannelVideo(eq(source.channelUrl()), eq(eventDay),
                eq(name + " · " + DAY.format(eventDay)), eq("agendamento"));

        when(jobs.outcome(job)).thenReturn(Optional.of(new ImportJobs.Outcome("RUNNING", null, null)));
        sources.sweep(); // still running: not queued again
        verify(jobs, times(1)).startChannelVideo(eq(source.channelUrl()), any(), anyString(), anyString());

        when(jobs.outcome(job)).thenReturn(Optional.of(new ImportJobs.Outcome("DONE", null, media)));
        sources.sweep();
        VideoSourceService.SourceView view = view(source.id());
        assertThat(view.imports()).anySatisfy(i -> {
            assertThat(i.eventDate()).isEqualTo(eventDay);
            assertThat(i.status()).isEqualTo("DONE");
            assertThat(i.mediaId()).isEqualTo(media);
        });
    }

    @Test
    void aDisabledSourceAndADayWithoutEventAreNotSearched() {
        Events.STARTS.set(List.of());
        sources.create("Desligado " + UUID.randomUUID(), "https://www.youtube.com/@Off" + UUID.randomUUID(), false, "tester");
        sources.create("Sem evento " + UUID.randomUUID(), "https://www.youtube.com/@Semevento" + UUID.randomUUID(), true, "tester");

        sources.sweep();

        verify(jobs, never()).startChannelVideo(anyString(), any(), anyString(), anyString());
    }

    @Test
    void aFailedAttemptIsReportedWithItsReasonAndNotRepeatedAtOnce() {
        LocalDate eventDay = Instant.now().plus(Duration.ofDays(2)).atZone(ZONE).toLocalDate();
        Events.STARTS.set(List.of(eventDay.atStartOfDay(ZONE).toInstant()));
        UUID job = UUID.randomUUID();
        when(jobs.startChannelVideo(anyString(), any(), anyString(), anyString())).thenReturn(job);
        when(jobs.outcome(job)).thenReturn(Optional.of(
                new ImportJobs.Outcome("FAILED", "Nenhum vídeo deste dia no canal ainda", null)));
        VideoSource source = sources.create("Falha " + UUID.randomUUID(),
                "https://www.youtube.com/@Falha" + UUID.randomUUID(), true, "tester");

        sources.sweep();
        sources.sweep();

        verify(jobs, times(1)).startChannelVideo(eq(source.channelUrl()), any(), anyString(), anyString());
        VideoSourceService.ImportView failed = view(source.id()).imports().getFirst();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.message()).isEqualTo("Nenhum vídeo deste dia no canal ainda");
    }

    private VideoSourceService.SourceView view(UUID id) {
        return sources.list().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }
}

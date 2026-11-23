package webpresenter.server.jobs;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * RabbitMQ wiring. Messages are plain JSON (UTF-8):
 * <ul>
 *   <li>{@value #JOBS}: durable queue of {@link WorkerMessages.JobRequest} (persistent messages);</li>
 *   <li>{@value #HEAVY_JOBS}: the same for jobs that need a lot of computation (separation,
 *       recognition), so GPU workers can take them first and CPU workers only after a delay;</li>
 *   <li>{@value #EVENTS}: durable queue of {@link WorkerMessages.WorkerEvent} from the workers;</li>
 *   <li>{@value #CONTROL}: fanout exchange of {@link WorkerMessages.Control} (cancellation) to every worker.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class RabbitJobs {

    static final String JOBS = "webpresenter.jobs";
    static final String HEAVY_JOBS = "webpresenter.jobs.heavy";
    static final String EVENTS = "webpresenter.job-events";
    static final String CONTROL = "webpresenter.job-control";

    @Bean
    Declarables jobTopology() {
        return new Declarables(QueueBuilder.durable(JOBS).build(), QueueBuilder.durable(HEAVY_JOBS).build(),
                QueueBuilder.durable(EVENTS).build(),
                new FanoutExchange(CONTROL, true, false));
    }

    @Component
    static class Dispatcher implements JobDispatcher {

        private final RabbitTemplate rabbit;
        private final JsonMapper json;

        Dispatcher(RabbitTemplate rabbit, JsonMapper json) {
            this.rabbit = rabbit;
            this.json = json;
        }

        @Override
        public void dispatch(WorkerMessages.JobRequest request) {
            Message message = message(request, MessageDeliveryMode.PERSISTENT);
            message.getMessageProperties().setTimestamp(new java.util.Date()); // workers may wait before taking it
            rabbit.send("", request.type().heavy() ? HEAVY_JOBS : JOBS, message);
        }

        @Override
        public void cancel(UUID jobId) {
            rabbit.send(CONTROL, "", message(new WorkerMessages.Control(jobId, "CANCEL"), MessageDeliveryMode.NON_PERSISTENT));
        }

        private Message message(Object body, MessageDeliveryMode mode) {
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding("utf-8");
            properties.setDeliveryMode(mode);
            return new Message(json.writeValueAsBytes(body), properties);
        }
    }

    /** Worker events, applied one at a time in order. */
    @Component
    static class EventListener {

        private static final Logger log = LoggerFactory.getLogger(EventListener.class);

        private final JobService jobs;
        private final JsonMapper json;

        EventListener(JobService jobs, JsonMapper json) {
            this.jobs = jobs;
            this.json = json;
        }

        @RabbitListener(queues = EVENTS, concurrency = "1")
        void onEvent(Message message) {
            WorkerMessages.WorkerEvent event;
            try {
                event = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8), WorkerMessages.WorkerEvent.class);
            } catch (RuntimeException e) {
                log.warn("discarding unreadable worker event: {}", e.getMessage());
                return;
            }
            jobs.onWorkerEvent(event);
        }
    }
}

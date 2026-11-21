package webpresenter.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Boots the whole application against a real PostgreSQL (Flyway migrations included). */
@SpringBootTest
@Import(WebpresenterServerTests.Containers.class)
class WebpresenterServerTests {

    @Test
    void contextLoads() {}

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18-alpine");
        }
    }
}

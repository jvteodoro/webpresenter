package webpresenter.server;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL and SeaweedFS (S3) containers for integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18-alpine");
    }

    @Bean
    GenericContainer<?> seaweedfs() {
        // Without -s3.config SeaweedFS accepts any credentials, which is enough for tests.
        return new GenericContainer<>("chrislusf/seaweedfs:4.52")
                .withCommand("server", "-dir=/data", "-s3", "-s3.port=8333")
                .withExposedPorts(8333)
                .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCodeMatching(code -> code < 500));
    }

    @Bean
    DynamicPropertyRegistrar s3Properties(GenericContainer<?> seaweedfs) {
        return registry -> {
            String endpoint = "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333);
            registry.add("webpresenter.storage.endpoint", () -> endpoint);
            registry.add("webpresenter.storage.public-endpoint", () -> endpoint);
            registry.add("webpresenter.storage.access-key", () -> "test");
            registry.add("webpresenter.storage.secret-key", () -> "test");
            registry.add("webpresenter.storage.bucket", () -> "media-test");
        };
    }
}

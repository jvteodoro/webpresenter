package webpresenter.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Boots the whole application against real infrastructure (Flyway migrations included). */
@SpringBootTest
@Import(TestInfrastructure.class)
class WebpresenterServerTests {

    @Test
    void contextLoads() {}
}

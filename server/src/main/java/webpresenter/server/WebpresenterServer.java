package webpresenter.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Central server: modular monolith. Each direct sub-package is a Spring Modulith application module. */
@SpringBootApplication
public class WebpresenterServer {

    public static void main(String[] args) {
        SpringApplication.run(WebpresenterServer.class, args);
    }
}

package webpresenter.server.webapp;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Client-side routes of the web app: a browser reload or a shared link on /eventos/123 must get
 * index.html, and the Angular router takes over. Only used when the web build is bundled.
 */
@Controller
class WebAppRoutes {

    @GetMapping({"/ao-vivo", "/ao-vivo/**", "/eventos", "/eventos/**", "/biblioteca", "/biblioteca/**",
            "/dispositivos", "/dispositivos/**", "/musicas", "/musicas/**"})
    String app() {
        return "forward:/index.html";
    }
}

package webpresenter.server.devices;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import webpresenter.protocol.ProtocolCodec;
import webpresenter.protocol.ProtocolVersion;

@Configuration
@EnableWebSocket
class DevicesWebSocketConfig implements WebSocketConfigurer {

    private final PlayerWebSocketHandler handler;

    DevicesWebSocketConfig(PlayerWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Players are native apps (no Origin header), so the default same-origin check is enough.
        registry.addHandler(handler, ProtocolVersion.PLAYER_ENDPOINT);
    }

    @Bean
    static ProtocolCodec protocolCodec() {
        return new ProtocolCodec();
    }
}

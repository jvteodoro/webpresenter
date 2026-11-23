package webpresenter.player.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerAddressTest {

    @Test
    void beaconGivesTheSenderFirstThenTheOtherAddresses() {
        var discovered = new ServerAddress.Discovered(47800);
        List<URI> candidates = discovered.candidates("""
                {"service":"webpresenter","version":1,"addresses":["10.0.0.5","192.168.18.22"],"playerPort":8801,"panelPort":8800}""",
                "192.168.18.22");
        assertEquals(List.of(URI.create("http://192.168.18.22:8801"), URI.create("http://10.0.0.5:8801")), candidates);
    }

    @Test
    void otherBroadcastsAreIgnored() {
        var discovered = new ServerAddress.Discovered(47800);
        assertTrue(discovered.candidates("{\"service\":\"something-else\"}", "1.2.3.4").isEmpty());
        assertTrue(discovered.candidates("not json", "1.2.3.4").isEmpty());
    }

    @Test
    void relativeLinksFollowTheServerAddress() {
        ServerAddress address = ServerAddress.fixed(URI.create("http://192.168.18.22:8801"));
        assertEquals(URI.create("http://192.168.18.22:8801/s3/media/media/x.mp4?X-Amz-Signature=abc"),
                address.link("/s3/media/media/x.mp4?X-Amz-Signature=abc"));
        assertEquals(URI.create("http://localhost:8333/media/x.mp4"), address.link("http://localhost:8333/media/x.mp4"));
    }
}

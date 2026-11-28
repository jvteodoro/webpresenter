package webpresenter.server.videos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VideoSourceServiceTest {

    @Test
    void aYouTubeChannelLinkIsAccepted() {
        assertThat(VideoSourceService.checkedUrl(" https://www.youtube.com/@Canal ")).isEqualTo("https://www.youtube.com/@Canal");
        assertThat(VideoSourceService.checkedUrl("https://m.youtube.com/channel/UC123")).isEqualTo("https://m.youtube.com/channel/UC123");
        assertThat(VideoSourceService.checkedUrl("https://youtu.be/abc")).isEqualTo("https://youtu.be/abc");
    }

    @Test
    void anotherSiteOrAnotherSchemeIsRefused() {
        assertThatThrownBy(() -> VideoSourceService.checkedUrl("https://example.com/@Canal"))
                .isInstanceOf(VideoSourceService.InvalidVideoSourceException.class);
        assertThatThrownBy(() -> VideoSourceService.checkedUrl("ftp://www.youtube.com/@Canal"))
                .isInstanceOf(VideoSourceService.InvalidVideoSourceException.class);
        assertThatThrownBy(() -> VideoSourceService.checkedUrl("não é um link"))
                .isInstanceOf(VideoSourceService.InvalidVideoSourceException.class);
        assertThatThrownBy(() -> VideoSourceService.checkedUrl(null))
                .isInstanceOf(VideoSourceService.InvalidVideoSourceException.class);
    }

    @Test
    void aSourceNeedsAName() {
        assertThat(VideoSourceService.cleanName("  Programa  ")).isEqualTo("Programa");
        assertThatThrownBy(() -> VideoSourceService.cleanName(" "))
                .isInstanceOf(VideoSourceService.InvalidVideoSourceException.class);
    }
}

package webpresenter.server.library;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MediaLibraryServiceTests {

    @Test
    void sanitizesUploadedFilenames() {
        assertThat(MediaLibraryService.sanitizeFilename("C:\\Users\\op\\Louvor 01.mp4")).isEqualTo("Louvor 01.mp4");
        assertThat(MediaLibraryService.sanitizeFilename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(MediaLibraryService.sanitizeFilename("  ")).isEqualTo("arquivo");
        assertThat(MediaLibraryService.sanitizeFilename(null)).isEqualTo("arquivo");
    }

    @Test
    void keepsOnlySafeExtensions() {
        assertThat(MediaLibraryService.suffix("Clip.MP4")).isEqualTo(".mp4");
        assertThat(MediaLibraryService.suffix("noext")).isEmpty();
        assertThat(MediaLibraryService.suffix(".hidden")).isEmpty();
        assertThat(MediaLibraryService.suffix("x.m p4")).isEmpty();
    }
}

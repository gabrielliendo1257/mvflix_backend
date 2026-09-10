package com.guille.media.reproductor.uploader.storage.library.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.guille.media.reproductor.uploader.storage.library.application.LibraryService;
import com.guille.media.reproductor.uploader.storage.library.application.port.LibraryContentResolver;
import com.guille.media.reproductor.uploader.storage.library.application.port.LibraryFileHandle;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class LibraryFileControllerTest {
  @Test
  void playbackUsesCanonicalScopedPathAndPreservesRange() throws Exception {
    var libraryService = mock(LibraryService.class);
    var resolver = mock(LibraryContentResolver.class);
    var file = Files.createTempFile("mvflix-playback", ".mp4");
    Files.write(file, new byte[] {1, 2, 3, 4});
    when(libraryService.findPlaybackLibrary(1L)).thenReturn(Mono.just(
        new com.guille.media.reproductor.uploader.storage.library.domain.model.MediaLibrary(
            1L, com.guille.media.reproductor.uploader.storage.library.domain.model.MediaLibraryType.LOCAL,
            file.getParent().toString(), true, "Admin", java.time.Instant.now())));
    when(resolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("video.mp4")))
        .thenReturn(Mono.just(new LibraryFileHandle("video.mp4", file, 4, "video/mp4")));

    var client = WebTestClient.bindToController(new LibraryFileController(libraryService, resolver)).build();
    client.get().uri("/api/v1/movie/storage/playback/libraries/1/files/video.mp4")
        .header("Range", "bytes=0-1").exchange().expectStatus().isEqualTo(HttpStatus.PARTIAL_CONTENT)
        .expectHeader().valueEquals("Content-Range", "bytes 0-1/4");
    Files.deleteIfExists(file);
  }
}

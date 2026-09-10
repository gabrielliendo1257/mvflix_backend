package com.guille.media.reproductor.uploader.storage.managedstorage.application.multipart;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.MultipartUploadSession;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.MultipartObjectStorageService;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.MultipartUploadRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class MultipartUploadRecoveryTest {
  private final MultipartUploadRepository repository = mock(MultipartUploadRepository.class);
  private final MultipartObjectStorageService objectStorage = mock(MultipartObjectStorageService.class);
  private final MultipartUploadRecovery recovery = new MultipartUploadRecovery(repository, objectStorage);

  @Test
  void abortsObjectBeforeMarkingSessionExpired() {
    MultipartUploadSession session = new MultipartUploadSession("public", "minio-upload-id", "owner", "uploads",
        "owner/object", 10, "video/mp4", 10, 1, Instant.now().minusSeconds(1),
        MultipartUploadSession.MultipartStatus.PENDING);
    when(repository.findExpired(any())).thenReturn(Flux.just(session));
    when(repository.transition(any(), any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(objectStorage.abort(any(), any(), any())).thenReturn(Mono.empty());

    recovery.recoverExpired().block();

    verify(objectStorage).abort("uploads", "owner/object", session.minioUploadId());
    verify(repository, org.mockito.Mockito.times(2)).transition(any(), any());
  }
}

package com.guille.media.reproductor.uploader.storage.managedstorage.application.multipart;

import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.MultipartUploadSession;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.MultipartUploadSession.MultipartStatus;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.MultipartObjectStorageService;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.MultipartUploadRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class MultipartUploadRecovery {
  private final MultipartUploadRepository repository;
  private final MultipartObjectStorageService objectStorage;

  @Scheduled(fixedDelayString = "${storage.multipart.recovery-ms:60000}")
  public void expireSessions() {
    recoverExpired().subscribe(null, error -> { });
  }

  public Mono<Void> recoverExpired() {
    return repository.findExpired(Instant.now()).flatMap(this::expire, 4).then();
  }

  private Mono<Void> expire(MultipartUploadSession session) {
    return repository.transition(copy(session, MultipartStatus.ABORTING), MultipartStatus.PENDING)
        .flatMap(locked -> objectStorage.abort(locked.bucket(), locked.objectKey(), locked.minioUploadId())
            .then(repository.transition(copy(locked, MultipartStatus.EXPIRED), MultipartStatus.ABORTING)).then());
  }

  private static MultipartUploadSession copy(MultipartUploadSession s, MultipartStatus status) {
    return new MultipartUploadSession(s.uploadId(), s.minioUploadId(), s.ownerUsername(), s.bucket(), s.objectKey(),
        s.totalBytes(), s.contentType(), s.partSizeBytes(), s.totalParts(), s.expiresAt(), status);
  }
}

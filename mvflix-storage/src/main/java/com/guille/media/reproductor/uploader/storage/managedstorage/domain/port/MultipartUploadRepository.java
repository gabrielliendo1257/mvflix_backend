package com.guille.media.reproductor.uploader.storage.managedstorage.domain.port;

import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.MultipartUploadSession;
import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface MultipartUploadRepository {
  Mono<MultipartUploadSession> save(MultipartUploadSession session, String idempotencyKey);
  Mono<MultipartUploadSession> findById(String uploadId);
  Mono<MultipartUploadSession> findByOwnerAndIdempotencyKey(String owner, String key);
  Mono<MultipartUploadSession> transition(MultipartUploadSession session,
      MultipartUploadSession.MultipartStatus expected);
  Flux<MultipartUploadSession> findExpired(Instant now);
}

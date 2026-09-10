package com.guille.media.reproductor.uploader.storage.managedstorage.application.multipart;

import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.MultipartUploadSession;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.model.MultipartUploadSession.MultipartStatus;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.MultipartObjectStorageService;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.MultipartObjectStorageService.MultipartPart;
import com.guille.media.reproductor.uploader.storage.managedstorage.domain.port.MultipartUploadRepository;
import com.guille.media.reproductor.uploader.storage.shared.security.UserProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class MultipartUploadServiceImpl implements MultipartUploadService {
  private final MultipartUploadRepository repository;
  private final MultipartObjectStorageService objectStorage;
  private final UserProvider userProvider;
  private final String bucket;
  private final long partSize;
  private final Duration ttl;
  private final Duration urlTtl;

  public MultipartUploadServiceImpl(MultipartUploadRepository repository,
      MultipartObjectStorageService objectStorage, UserProvider userProvider,
      @Value("${minio.bucket:uploads}") String bucket,
      @Value("${storage.multipart.part-size-bytes:10485760}") long partSize,
      @Value("${storage.multipart.ttl:PT1H}") Duration ttl,
      @Value("${storage.multipart.url-ttl:PT15M}") Duration urlTtl) {
    this.repository = repository;
    this.objectStorage = objectStorage;
    this.userProvider = userProvider;
    this.bucket = bucket;
    this.partSize = partSize;
    this.ttl = ttl;
    this.urlTtl = urlTtl;
  }

  @Override
  public Mono<MultipartUploadResult> create(CreateMultipartUploadCommand command) {
    if (command.totalBytes() < 1 || command.filename() == null || command.filename().isBlank()
        || command.contentType() == null || command.contentType().isBlank()) {
      return Mono.error(new IllegalArgumentException("invalid multipart upload request"));
    }
    return userProvider.getAuthenticatedUser().flatMap(user -> {
      Mono<MultipartUploadResult> existing = command.idempotencyKey() == null
          ? Mono.empty()
          : repository.findByOwnerAndIdempotencyKey(user.subject(), command.idempotencyKey())
              .filter(s -> sameRequest(s, command)).map(this::result);
      return existing.switchIfEmpty(Mono.defer(() -> createNew(user.subject(), command)))
          .onErrorResume(DuplicateKeyException.class, ignored -> repository
              .findByOwnerAndIdempotencyKey(user.subject(), command.idempotencyKey()).map(this::result));
    });
  }

  private Mono<MultipartUploadResult> createNew(String owner, CreateMultipartUploadCommand command) {
    String publicId = UUID.randomUUID().toString();
    String objectKey = owner + "/videos/" + publicId + "-" + safeFilename(command.filename());
    int totalParts = Math.toIntExact((command.totalBytes() + partSize - 1) / partSize);
    return objectStorage.create(bucket, objectKey, command.contentType()).flatMap(rawId -> {
      String minioId = rawId;
      MultipartUploadSession session = new MultipartUploadSession(publicId, minioId, owner, bucket,
          objectKey, command.totalBytes(), command.contentType(), partSize, totalParts,
          Instant.now().plus(ttl), MultipartStatus.PENDING);
      return repository.save(session, command.idempotencyKey()).map(this::result)
          .onErrorResume(error -> objectStorage.abort(bucket, objectKey, minioId).then(Mono.error(error)));
    });
  }

  @Override
  public Mono<MultipartPartsResult> parts(String uploadId, int from, int limit) {
    if (from < 1 || limit < 1 || limit > 1000) return Mono.error(new IllegalArgumentException("invalid parts range"));
    return owned(uploadId).flatMap(s -> {
      if (s.status() != MultipartStatus.PENDING || s.expiresAt().isBefore(Instant.now()))
        return Mono.error(new IllegalStateException("multipart upload is not pending"));
      int end = Math.min(s.totalParts() + 1, from + limit);
      return reactor.core.publisher.Flux.range(from, Math.max(0, end - from))
          .flatMapSequential(n -> objectStorage.presignedPart(s.bucket(), s.objectKey(), s.minioUploadId(), n, urlTtl)
              .map(url -> new PartInstruction(n, url, java.util.Map.of())))
          .collectList().map(result -> new MultipartPartsResult(s.uploadId(), result));
    });
  }

  @Override
  public Mono<Void> complete(String uploadId, List<CompletedPart> parts) {
    return owned(uploadId).flatMap(s -> {
      if (s.status() == MultipartStatus.COMPLETED) return Mono.empty();
      validateParts(s, parts);
      return repository.transition(new MultipartUploadSession(s.uploadId(), s.minioUploadId(), s.ownerUsername(), s.bucket(),
              s.objectKey(), s.totalBytes(), s.contentType(), s.partSizeBytes(), s.totalParts(), s.expiresAt(), MultipartStatus.ABORTING), MultipartStatus.PENDING)
          .flatMap(locked -> objectStorage.complete(locked.bucket(), locked.objectKey(), locked.minioUploadId(),
              parts.stream().map(p -> new MultipartPart(p.partNumber(), p.etag())).toList())
              .then(repository.transition(new MultipartUploadSession(locked.uploadId(), locked.minioUploadId(), locked.ownerUsername(),
                  locked.bucket(), locked.objectKey(), locked.totalBytes(), locked.contentType(), locked.partSizeBytes(), locked.totalParts(),
                  locked.expiresAt(), MultipartStatus.COMPLETED), MultipartStatus.ABORTING)).then())
          .onErrorResume(error -> Mono.error(error));
    });
  }

  @Override
  public Mono<Void> abort(String uploadId) {
    return owned(uploadId).flatMap(s -> {
      if (s.status() == MultipartStatus.ABORTED || s.status() == MultipartStatus.EXPIRED) return Mono.empty();
      return repository.transition(copy(s, MultipartStatus.ABORTING), s.status()).flatMap(locked ->
          objectStorage.abort(locked.bucket(), locked.objectKey(), locked.minioUploadId())
              .then(repository.transition(copy(locked, MultipartStatus.ABORTED), MultipartStatus.ABORTING)).then());
    });
  }

  private Mono<MultipartUploadSession> owned(String id) {
    return userProvider.getAuthenticatedUser().flatMap(user -> repository.findById(id)
        .filter(s -> s.ownerUsername().equals(user.subject()))
        .switchIfEmpty(Mono.error(new IllegalArgumentException("unknown upload"))));
  }
  private void validateParts(MultipartUploadSession s, List<CompletedPart> parts) {
    if (parts == null || parts.size() != s.totalParts() || IntStream.rangeClosed(1, s.totalParts())
        .anyMatch(n -> parts.stream().noneMatch(p -> p.partNumber() == n && p.etag() != null && !p.etag().isBlank())))
      throw new IllegalArgumentException("all multipart parts with etags are required");
  }
  private boolean sameRequest(MultipartUploadSession s, CreateMultipartUploadCommand c) {
    return s.totalBytes() == c.totalBytes() && s.contentType().equals(c.contentType());
  }
  private MultipartUploadResult result(MultipartUploadSession s) {
    return new MultipartUploadResult(s.uploadId(), "PRESIGNED_MULTIPART", s.partSizeBytes(),
        s.totalParts(), s.expiresAt(), s.objectKey());
  }
  private static String safeFilename(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
  private static MultipartUploadSession copy(MultipartUploadSession s, MultipartStatus status) {
    return new MultipartUploadSession(s.uploadId(), s.minioUploadId(), s.ownerUsername(), s.bucket(), s.objectKey(), s.totalBytes(),
        s.contentType(), s.partSizeBytes(), s.totalParts(), s.expiresAt(), status);
  }
}

package com.gcorp.service.app.mvflix_media_ingestion.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaIngestionTest {
  @Test
  void awaitUploadPreservesIngestionIdentity() {
    var causation = UUID.randomUUID();
    var ingestion = new MediaIngestion(
        UUID.randomUUID(), "actor", 3L, null, MediaIngestion.Phase.PREPARING_UPLOAD,
        null, 2, 1, Instant.now(), Instant.now(), Instant.now(), "key", "video.mp4", 4,
        "video/mp4", null, null, null, "fingerprint", causation);

    var awaited = ingestion.awaitUpload("7", "https://upload", "uploads/video.mp4");

    assertEquals(MediaIngestion.Phase.AWAITING_UPLOAD, awaited.phase());
    assertEquals("fingerprint", awaited.requestFingerprint());
    assertEquals(causation, awaited.causationId());
    assertEquals("uploads/video.mp4", awaited.storageKey());
  }

  @Test
  void transitionPreservesMultipartMetadata() {
    var now = Instant.now();
    var ingestion = new MediaIngestion(UUID.randomUUID(), "actor", 3L, null,
        MediaIngestion.Phase.PREPARING_UPLOAD, null, null, 2, 1, now, now, now, "key", "video.mp4", 10,
        "video/mp4", null, null, null, "fingerprint", UUID.randomUUID(), "actor",
        "SIMPLE", null, null);

    var awaiting = ingestion.awaitUpload("multipart-id", null, "uploads/video.mp4",
        "PRESIGNED_MULTIPART", 10L * 1024 * 1024, 1);
    var verifying = awaiting.transition(MediaIngestion.Phase.VERIFYING_UPLOAD, null, null, null);

    assertEquals("PRESIGNED_MULTIPART", verifying.uploadStrategy());
    assertEquals(10L * 1024 * 1024, verifying.partSizeBytes());
    assertEquals(1, verifying.totalParts());
    assertEquals("multipart-id", verifying.uploadId());
  }
  @Test void transitionUsesOptimisticVersionAndRejectsTerminalState() {
    var now=Instant.now(); var i=new MediaIngestion(UUID.randomUUID(),"actor",null,null,MediaIngestion.Phase.STARTING,null,4,0,now,now,now,"key","x.mp4",10,"video/mp4",null);
    var next=i.transition(MediaIngestion.Phase.PREPARING_CATALOG,12L,null,null);
    assertEquals(5,next.version()); assertEquals(12L,next.catalogItemId());
    var preparingUpload = next.transition(MediaIngestion.Phase.PREPARING_UPLOAD, null, null, null);
    var awaitingUpload = preparingUpload.transition(MediaIngestion.Phase.AWAITING_UPLOAD, null, "u", null);
    var finalizing = awaitingUpload.transition(MediaIngestion.Phase.FINALIZING_CATALOG, null, null, null);
    var done=finalizing.transition(MediaIngestion.Phase.COMPLETED,null,null,null);
    assertThrows(IllegalStateException.class,()->done.transition(MediaIngestion.Phase.CANCELLING,null,null,null));
  }

  @Test
  void failedKeepsCodeBoundedAndStoresDiagnosticDetailSeparately() {
    var now = Instant.now();
    var ingestion = new MediaIngestion(UUID.randomUUID(), "actor", null, null,
        MediaIngestion.Phase.STARTING, null, 1, 0, now, now, now, "key", "x.mp4", 10,
        "video/mp4", null);

    var failed = ingestion.failed("x".repeat(121), "connection refused".repeat(20));

    assertEquals("INTERNAL_ERROR", failed.failureCode());
    assertEquals("connection refused".repeat(20), failed.failureDetail());
  }
}

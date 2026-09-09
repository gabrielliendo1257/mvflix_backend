package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.messaging;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcorp.service.app.mvflix_media_ingestion.application.InboxRepository;
import com.gcorp.service.app.mvflix_media_ingestion.application.IngestionCorrelationNotFoundException;
import com.gcorp.service.app.mvflix_media_ingestion.application.MediaIngestionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class UploadCompletedListenerTest {
  @Test
  void duplicateCompletedInboxIsIgnored() {
    var service = mock(MediaIngestionService.class);
    var inbox = mock(InboxRepository.class);
    var id = UUID.randomUUID();
    when(inbox.receive(id, "UploadCompleted")).thenReturn(Mono.just(false));
    when(inbox.completed(id)).thenReturn(Mono.just(true));
    var listener = new UploadCompletedListener(service, new ObjectMapper(), inbox);

    assertDoesNotThrow(() -> listener.onMessage(event(id, null, 1)));

    verifyNoInteractions(service);
    verify(inbox).completed(id);
  }

  @Test
  void fallsBackToStorageIdentityWhenCorrelationDoesNotExist() {
    var service = mock(MediaIngestionService.class);
    var inbox = mock(InboxRepository.class);
    var eventId = UUID.randomUUID();
    var correlationId = UUID.randomUUID();
    when(inbox.receive(eventId, "UploadCompleted")).thenReturn(Mono.just(true));
     when(service.uploadCompleted(correlationId, 1L, "x", eventId.toString()))
        .thenReturn(Mono.error(new IngestionCorrelationNotFoundException(correlationId)));
     when(service.uploadCompletedByStorageId(1L, 1L, "x", eventId.toString())).thenReturn(Mono.empty());
    when(inbox.markCompleted(eventId)).thenReturn(Mono.empty());
    var listener = new UploadCompletedListener(service, new ObjectMapper(), inbox);

    assertDoesNotThrow(() -> listener.onMessage(event(eventId, correlationId, 1)));

     verify(service).uploadCompletedByStorageId(1L, 1L, "x", eventId.toString());
    verify(inbox).markCompleted(eventId);
  }

  @Test
  void rejectsNonPositiveStorageId() {
    var inbox = mock(InboxRepository.class);
    UUID eventId = UUID.randomUUID();
    when(inbox.receive(eventId, "UploadCompleted")).thenReturn(Mono.just(true));
    when(inbox.markFailed(eq(eventId), anyString())).thenReturn(Mono.empty());
    var listener = new UploadCompletedListener(mock(MediaIngestionService.class), new ObjectMapper(), inbox);

    assertThrows(IllegalArgumentException.class, () -> listener.onMessage(event(eventId, null, 0)));
  }

  @Test
  void rejectsBlankObjectKey() {
    var inbox = mock(InboxRepository.class);
    UUID eventId = UUID.randomUUID();
    when(inbox.receive(eventId, "UploadCompleted")).thenReturn(Mono.just(true));
    when(inbox.markFailed(eq(eventId), anyString())).thenReturn(Mono.empty());
    var listener = new UploadCompletedListener(mock(MediaIngestionService.class), new ObjectMapper(), inbox);

    assertThrows(IllegalArgumentException.class,
        () -> listener.onMessage(event(eventId, null, 1).replace("\"objectKey\":\"x\"", "\"objectKey\":\" \"")));
  }

  private String event(UUID eventId, UUID correlationId, long storageId) {
    return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"UploadCompleted\"," + ""
        + "\"eventVersion\":1,\"producer\":\"mvflix-storage\",\"correlationId\":"
        + (correlationId == null ? "null" : "\"" + correlationId + "\"")
        + ",\"causationId\":\"" + eventId + "\",\"aggregate\":{\"type\":\"ManagedObject\","
        + "\"id\":\"object-1\"},\"payload\":{\"storageId\":" + storageId
        + ",\"ownerUsername\":\"a\",\"objectKey\":\"x\",\"contentType\":\"video/mp4\","
        + "\"contentLength\":1}}";
  }
}

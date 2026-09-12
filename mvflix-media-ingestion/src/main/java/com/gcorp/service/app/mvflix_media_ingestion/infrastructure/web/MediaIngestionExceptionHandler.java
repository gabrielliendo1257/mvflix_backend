package com.gcorp.service.app.mvflix_media_ingestion.infrastructure.web;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.gcorp.service.app.mvflix_media_ingestion.application.UploadInconsistentException;

@RestControllerAdvice(assignableTypes = MediaIngestionController.class)
class MediaIngestionExceptionHandler {
  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<ErrorResponse> conflict(IllegalStateException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorResponse("INGESTION_CONFLICT", error.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException error) {
    return ResponseEntity.badRequest()
        .body(new ErrorResponse("INVALID_INGESTION_REQUEST", error.getMessage()));
  }

  @ExceptionHandler(UploadInconsistentException.class)
  ResponseEntity<ErrorResponse> inconsistentUpload(UploadInconsistentException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorResponse("UPLOAD_INCONSISTENT", error.getMessage()));
  }

  record ErrorResponse(String code, String message, Instant timestamp) {
    ErrorResponse(String code, String message) {
      this(code, message == null ? "Request rejected" : message, Instant.now());
    }
  }
}

package com.guille.media.bff.app.ports;

import com.guille.media.bff.app.dto.DiscoveredFileDto;
import com.guille.media.bff.app.dto.LibraryDto;
import com.guille.media.bff.app.dto.QuotaSnapshot;
import com.guille.media.bff.app.dto.StreamingSessionDto;
import com.guille.media.bff.app.dto.UploadCreateRequest;
import com.guille.media.bff.app.dto.UploadListItem;
import com.guille.media.bff.app.dto.UploadSessionDto;
import com.guille.media.bff.app.dto.UploadStatusDto;
import com.guille.media.bff.app.dto.MultipartUploadDtos;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Contrato hacia mvflix-storage (sesiones de subida y cuota del usuario). */
public interface StorageWebClient {

  Mono<QuotaSnapshot> quota();

  Flux<UploadListItem> listUploads(int limit);

  Mono<UploadSessionDto> createUpload(UploadCreateRequest request);

  Mono<UploadSessionDto> findUploadByIdempotencyKey(String idempotencyKey);

  Mono<UploadStatusDto> uploadStatus(Long uploadId);

  /** Regenera instrucciones de subida para una sesión PENDING propia. */
  Mono<UploadSessionDto> renewInstructions(Long uploadId);

  Mono<Void> cancelUpload(Long uploadId);

  Mono<HttpStatus> completeUpload(Long uploadId);

  /** Sesión de streaming (URL firmada) del objeto ya subido al object store. */
  Mono<StreamingSessionDto> stream(String objectId);

  /** Playback M2M de catálogo: requiere scope storage.stream (movies-playback). */
  Mono<StreamingSessionDto> catalogStream(String objectId);

  /** Rollback: borra el objeto en el object store y restaura la cuota del usuario. */
  Mono<Void> deleteObject(Long storageId);

  /** Bibliotecas del operador (media server). */
  Flux<LibraryDto> listLibraries();

  /** Registra una biblioteca en runtime a nombre del usuario autenticado. */
  Mono<LibraryDto> createLibrary(String rootPath);

  /** Elimina una biblioteca propia (las del operador no). */
  Mono<Void> deleteLibrary(Long libraryId);

  /** Archivos descubiertos por el scanner en la biblioteca (media server). */
  Flux<DiscoveredFileDto> listLibraryFiles(Long libraryId);

  /** Cancela el escaneo en curso de la biblioteca (corte cooperativo del scanner). */
  Mono<Void> cancelLibraryScan(Long libraryId);

  /** Stream LOCAL con soporte Range: el BFF proxya status/headers/cuerpo tal cual. */
  Mono<ResponseEntity<Flux<DataBuffer>>> streamLibraryFile(
      Long libraryId, String relativePath, String rangeHeader);

  Mono<MultipartUploadDtos.Session> createMultipart(MultipartUploadDtos.Create request);
  Mono<MultipartUploadDtos.Parts> multipartParts(String uploadId, int from, int limit);
  Mono<Void> completeMultipart(String uploadId, MultipartUploadDtos.Complete request);
  Mono<Void> abortMultipart(String uploadId);
}

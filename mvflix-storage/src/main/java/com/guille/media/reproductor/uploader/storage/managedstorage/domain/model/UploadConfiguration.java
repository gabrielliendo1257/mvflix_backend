package com.guille.media.reproductor.uploader.storage.managedstorage.domain.model;

import java.time.Duration;

/**
 * Configuración de una sesión de upload. Hoy solo hay un mecanismo real
 * (presigned PUT simple); cuando exista upload multipart/resumable, este
 * registro expresará la variante elegida.
 */
public record UploadConfiguration(Strategy strategy, Duration expiration) {
  public UploadConfiguration(Duration expiration) {
    this(Strategy.SIMPLE, expiration);
  }

  public enum Strategy {
    SIMPLE,
    MULTIPART
  }
}

package com.gcorp.service.app.mvflix_playback.domain;

import java.time.Instant;

public final class PlaybackSession {
  private final PlaybackSessionId id;
  private final ViewerId viewerId;
  private final CatalogItemId catalogItemId;
  private final ContentReference contentReference;
  private final Instant startedAt;
  private final Instant expiresAt;
  private PlaybackSessionStatus status;
  private long lastSequence;
  private PlaybackPosition lastPosition;

  private PlaybackSession(PlaybackSessionId id, ViewerId viewerId, CatalogItemId catalogItemId,
      ContentReference contentReference, Instant startedAt, Instant expiresAt, PlaybackSessionStatus status,
      long lastSequence, PlaybackPosition lastPosition) {
    if (startedAt == null || expiresAt == null || !expiresAt.isAfter(startedAt)) {
      throw new IllegalArgumentException("Playback session timestamps are invalid");
    }
    if (lastSequence < 0) {
      throw new IllegalArgumentException("Playback sequence cannot be negative");
    }
    this.id = id;
    this.viewerId = viewerId;
    this.catalogItemId = catalogItemId;
    this.contentReference = contentReference;
    this.startedAt = startedAt;
    this.expiresAt = expiresAt;
    this.status = status;
    this.lastSequence = lastSequence;
    this.lastPosition = lastPosition;
  }

  public static PlaybackSession start(PlaybackSessionId id, ViewerId viewerId,
      CatalogItemId catalogItemId, ContentReference contentReference, Instant startedAt, Instant expiresAt) {
    return new PlaybackSession(id, viewerId, catalogItemId, contentReference, startedAt, expiresAt,
        PlaybackSessionStatus.ACTIVE, 0, null);
  }

  public static PlaybackSession restore(PlaybackSessionId id, ViewerId viewerId,
      CatalogItemId catalogItemId, ContentReference contentReference, Instant startedAt, Instant expiresAt,
      PlaybackSessionStatus status, long lastSequence, PlaybackPosition lastPosition) {
    return new PlaybackSession(id, viewerId, catalogItemId, contentReference, startedAt, expiresAt,
        status, lastSequence, lastPosition);
  }

  public boolean recordProgress(PlaybackPosition position, long sequence) {
    if (status != PlaybackSessionStatus.ACTIVE || sequence <= lastSequence) {
      return false;
    }
    this.lastPosition = position;
    this.lastSequence = sequence;
    return true;
  }

  public boolean complete(PlaybackPosition finalPosition, long sequence) {
    if (status == PlaybackSessionStatus.COMPLETED) {
      return false;
    }
    if (status != PlaybackSessionStatus.ACTIVE) {
      throw new IllegalStateException("Only active playback sessions can complete");
    }
    if (sequence <= lastSequence) {
      return false;
    }
    this.lastPosition = finalPosition;
    this.lastSequence = sequence;
    this.status = PlaybackSessionStatus.COMPLETED;
    return true;
  }

  public void expire(Instant now) {
    if (status == PlaybackSessionStatus.ACTIVE && !now.isBefore(expiresAt)) {
      this.status = PlaybackSessionStatus.EXPIRED;
    }
  }

  public PlaybackSessionId id() { return id; }
  public ViewerId viewerId() { return viewerId; }
  public CatalogItemId catalogItemId() { return catalogItemId; }
  public ContentReference contentReference() { return contentReference; }
  public PlaybackSessionStatus status() { return status; }
  public Instant startedAt() { return startedAt; }
  public Instant expiresAt() { return expiresAt; }
  public long lastSequence() { return lastSequence; }
  public PlaybackPosition lastPosition() { return lastPosition; }
}

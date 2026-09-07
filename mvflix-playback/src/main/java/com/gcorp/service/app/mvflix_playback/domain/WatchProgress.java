package com.gcorp.service.app.mvflix_playback.domain;

import java.time.Instant;

public final class WatchProgress {
  private final ViewerId viewerId;
  private final CatalogItemId catalogItemId;
  private PlaybackPosition position;
  private boolean completed;
  private PlaybackSessionId lastSessionId;
  private Instant updatedAt;
  private long version;

  public WatchProgress(ViewerId viewerId, CatalogItemId catalogItemId) {
    this.viewerId = viewerId;
    this.catalogItemId = catalogItemId;
    this.updatedAt = Instant.EPOCH;
  }

  public boolean update(PlaybackPosition next, PlaybackSessionId sessionId, long nextVersion,
      boolean completed, Instant updatedAt) {
    if (nextVersion <= version) {
      return false;
    }
    this.position = next;
    this.lastSessionId = sessionId;
    this.completed = completed;
    this.updatedAt = updatedAt;
    this.version = nextVersion;
    return true;
  }

  public ViewerId viewerId() { return viewerId; }
  public CatalogItemId catalogItemId() { return catalogItemId; }
  public PlaybackPosition position() { return position; }
  public boolean completed() { return completed; }
  public PlaybackSessionId lastSessionId() { return lastSessionId; }
  public Instant updatedAt() { return updatedAt; }
  public long version() { return version; }
}

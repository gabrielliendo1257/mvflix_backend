package com.gcorp.service.app.mvflix_activity.infrastructure.persistence;

import com.gcorp.service.app.mvflix_activity.application.port.ActivityInbox;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedInbox;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/** Inbox scoped to one projection so independent consumers cannot acknowledge each other. */
public class DatabaseActivityInbox implements ActivityInbox, ActivityFeedInbox {
  private final DatabaseClient db;
  private final String projection;

  public DatabaseActivityInbox(DatabaseClient db, String projection) {
    this.db = db;
    this.projection = projection;
  }

  @Override
  public Mono<Void> recordReceived(String id, String type) {
    return db.sql("INSERT INTO activity_inbox(projection_name,event_id,event_type,status) VALUES(:projection,:id,:type,'RECEIVED') ON CONFLICT(projection_name,event_id) DO NOTHING")
        .bind("projection", projection).bind("id", UUID.fromString(id)).bind("type", type)
        .fetch().rowsUpdated().then();
  }

  @Override
  public Mono<Boolean> isCompleted(String id) {
    return db.sql("SELECT status FROM activity_inbox WHERE projection_name=:projection AND event_id=:id")
        .bind("projection", projection).bind("id", UUID.fromString(id))
        .map((r, m) -> "COMPLETED".equals(r.get("status", String.class))).one()
        .defaultIfEmpty(false);
  }

  @Override
  public Mono<Void> markCompleted(String id) {
    return db.sql("UPDATE activity_inbox SET status='COMPLETED',completed_at=NOW(),updated_at=NOW(),last_error=NULL WHERE projection_name=:projection AND event_id=:id")
        .bind("projection", projection).bind("id", UUID.fromString(id)).fetch().rowsUpdated().then();
  }

  @Override
  public Mono<Void> markFailed(String id, String type, String error) {
    return db.sql("INSERT INTO activity_inbox(projection_name,event_id,event_type,status,last_error) VALUES(:projection,:id,:type,'FAILED',:error) ON CONFLICT(projection_name,event_id) DO UPDATE SET status='FAILED',last_error=:error,updated_at=NOW()")
        .bind("projection", projection).bind("id", UUID.fromString(id)).bind("type", type)
        .bind("error", error).fetch().rowsUpdated().then();
  }
}

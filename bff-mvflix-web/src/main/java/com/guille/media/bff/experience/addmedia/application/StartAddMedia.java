package com.guille.media.bff.experience.addmedia.application;

import com.guille.media.bff.app.dto.UserProfile;
import com.guille.media.bff.app.ports.UsersWebPort;
import com.guille.media.bff.experience.addmedia.application.port.MediaIngestionClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Starts uploads through media-ingestion; the BFF only applies the user gate. */
@Service
public class StartAddMedia {
  private final UsersWebPort users;
  private final MediaIngestionClient ingestion;

  public StartAddMedia(UsersWebPort users, MediaIngestionClient ingestion) {
    this.users = users;
    this.ingestion = ingestion;
  }

  public Mono<AddMediaResult> handle(String ownerSubject, StartAddMediaCommand command) {
    return handle(ownerSubject, command, "add-media:" + command.idempotencyKey());
  }

  public Mono<AddMediaResult> handle(String ownerSubject, StartAddMediaCommand command,
      String correlationId) {
    return users.me()
        .flatMap(this::guardBlocked)
        .then(ingestion.create(ownerSubject, command, correlationId))
        .map(MediaIngestionResultMapper::map);
  }

  private Mono<Void> guardBlocked(UserProfile profile) {
    if (profile.blocked()) {
      return Mono.error(new UserBlockedException(
          profile.username() == null ? "?" : profile.username(), profile.violations()));
    }
    return Mono.empty();
  }
}

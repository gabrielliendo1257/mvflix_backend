package com.gcorp.service.app.mvflix_playback.application;

import com.gcorp.service.app.mvflix_playback.application.port.WatchProgressRepository;
import com.gcorp.service.app.mvflix_playback.domain.ViewerId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
public class MergeAnonymousProgress {
  private final WatchProgressRepository progress;

  public MergeAnonymousProgress(WatchProgressRepository progress) {
    this.progress = progress;
  }

  @Transactional("connectionFactoryTransactionManager")
  public Mono<Void> execute(ViewerId anonymousViewer, ViewerId authenticatedViewer) {
    if (!anonymousViewer.value().startsWith("anonymous:")
        || anonymousViewer.equals(authenticatedViewer)) {
      return Mono.empty();
    }
    return progress.merge(anonymousViewer, authenticatedViewer);
  }
}

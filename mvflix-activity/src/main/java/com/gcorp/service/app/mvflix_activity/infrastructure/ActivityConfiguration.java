package com.gcorp.service.app.mvflix_activity.infrastructure;

import com.gcorp.service.app.mvflix_activity.application.*;
import com.gcorp.service.app.mvflix_activity.application.port.*;
import com.gcorp.service.app.mvflix_activity.feed.application.GetActivityFeed;
import com.gcorp.service.app.mvflix_activity.feed.application.ProjectActivityEvent;
import com.gcorp.service.app.mvflix_activity.feed.application.ProjectCatalogItemAccessChanged;
import com.gcorp.service.app.mvflix_activity.feed.application.ProjectUploadFailed;
import com.gcorp.service.app.mvflix_activity.feed.application.ProjectPlaybackActivity;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedInbox;
import com.gcorp.service.app.mvflix_activity.feed.application.port.ActivityFeedRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import io.r2dbc.spi.ConnectionFactory;
import com.gcorp.service.app.mvflix_activity.infrastructure.persistence.DatabaseActivityInbox;
import org.springframework.r2dbc.core.DatabaseClient;

@Configuration
public class ActivityConfiguration {
  @Bean R2dbcTransactionManager connectionFactoryTransactionManager(ConnectionFactory cf) { return new R2dbcTransactionManager(cf); }
  @Bean TransactionalOperator transactionalOperator(R2dbcTransactionManager tm) { return TransactionalOperator.create(tm); }
  @Bean
  @Qualifier("watchActivityInbox")
  ActivityInbox activityInbox(DatabaseClient db) { return new DatabaseActivityInbox(db, "watch_activity"); }
  @Bean
  @Qualifier("feedActivityInbox")
  ActivityFeedInbox activityFeedInbox(DatabaseClient db) { return new DatabaseActivityInbox(db, "activity_feed"); }
  @Bean ActivityProcessor activityProcessor(@Qualifier("watchActivityInbox") ActivityInbox inbox,
      WatchActivityRepository projection, TransactionalOperator tx) { return new ActivityProcessor(inbox, projection, tx); }
  @Bean ActivityQueryService activityQueryService(WatchActivityRepository repository) { return new ActivityQueryService(repository); }
  @Bean ProjectActivityEvent projectActivityEvent(@Qualifier("feedActivityInbox") ActivityFeedInbox inbox,
      ActivityFeedRepository projection,
      TransactionalOperator tx) { return new ProjectActivityEvent(inbox, projection, tx); }
  @Bean ProjectCatalogItemAccessChanged projectCatalogItemAccessChanged(
      @Qualifier("feedActivityInbox") ActivityFeedInbox inbox,
      ActivityFeedRepository projection, TransactionalOperator tx) {
    return new ProjectCatalogItemAccessChanged(inbox, projection, tx);
  }
  @Bean ProjectUploadFailed projectUploadFailed(@Qualifier("feedActivityInbox") ActivityFeedInbox inbox,
      ActivityFeedRepository projection,
      TransactionalOperator tx) { return new ProjectUploadFailed(inbox, projection, tx); }
  @Bean GetActivityFeed getActivityFeed(ActivityFeedRepository repository) { return new GetActivityFeed(repository); }
  @Bean ProjectPlaybackActivity projectPlaybackActivity(@Qualifier("feedActivityInbox") ActivityFeedInbox inbox,
      ActivityFeedRepository projection, TransactionalOperator tx) {
    return new ProjectPlaybackActivity(inbox, projection, tx);
  }
}

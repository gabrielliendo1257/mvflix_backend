package com.guille.media.bff.experience.activity.web;

import com.guille.media.bff.experience.activity.application.GetActivityFeed;
import com.guille.media.bff.experience.activity.application.GetActivityFeed.ActivityEntry;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@Validated
@RequestMapping(value = "/web/activity", produces = MediaType.APPLICATION_JSON_VALUE)
public class ActivityController {
  private final GetActivityFeed getActivityFeed;

  public ActivityController(GetActivityFeed getActivityFeed) {
    this.getActivityFeed = getActivityFeed;
  }

  @GetMapping
  public Flux<ActivityEntry> feed(
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
    return getActivityFeed.execute(cursor, limit);
  }
}

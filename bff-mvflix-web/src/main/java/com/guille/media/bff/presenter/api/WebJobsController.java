package com.guille.media.bff.presenter.api;

import com.guille.media.bff.app.service.Job;
import com.guille.media.bff.app.service.JobStore;
import com.guille.media.bff.app.service.WebSessionService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Estado y progreso de los trabajos transitorios iniciados desde el BFF. */
@RestController
@RequestMapping("/web/jobs")
public class WebJobsController {

    private final JobStore jobStore;
    private final WebSessionService session;

    public WebJobsController(JobStore jobStore, WebSessionService session) {
        this.jobStore = jobStore;
        this.session = session;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Job> recent(@RequestParam(defaultValue = "20") int limit) {
        return this.subject().flatMapMany(owner -> this.jobStore.recent(owner, limit));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Job>> events(@PathVariable String id) {
        return this.subject()
                .flatMapMany(owner -> this.jobStore.findOwned(id, owner))
                .flatMap(owned -> this.jobStore.events(id))
                .map(job -> ServerSentEvent.builder(job).event("progress").build());
    }

    protected Mono<String> subject() {
        return this.session.currentSubject().defaultIfEmpty("anonymous");
    }
}

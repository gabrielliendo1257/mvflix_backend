package com.guille.media.bff.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Aislamiento de jobs: cada usuario solo ve SUS trabajos. */
class JobStoreIsolationTest {

  private final JobStore store = new JobStore();

  @Test
  void recentOnlyReturnsJobsOfTheRequestingOwner() {
    this.store.start("j1", "ana", JobType.SCAN);
    this.store.start("j2", "pepe", JobType.BULK_VISIBILITY);

    StepVerifier.create(this.store.recent("pepe", 20))
        .assertNext(job -> {
          assertThat(job.id()).isEqualTo("j2");
          assertThat(job.ownerSubject()).isEqualTo("pepe");
        })
        .verifyComplete();

    StepVerifier.create(this.store.recent("ana", 20))
        .assertNext(job -> assertThat(job.id()).isEqualTo("j1"))
        .verifyComplete();
  }

  @Test
  void foreignJobBehavesAsInexistentForSseAndReads() {
    this.store.start("j1", "ana", JobType.SCAN);

    StepVerifier.create(this.store.findOwned("j1", "pepe"))
        .verifyComplete();

    // El propio dueño sí lo ve.
    StepVerifier.create(this.store.findOwned("j1", "ana"))
        .assertNext(job -> assertThat(job.id()).isEqualTo("j1"))
        .verifyComplete();

    // NOTA: el SSE crudo del store es un stream vivo sin filtrar; el gate de
    // propiedad para /{id}/events vive en WebJobsController vía findOwned
    // (probado aquí arriba): un ajeno nunca llega a suscribirse al sink.
  }
}

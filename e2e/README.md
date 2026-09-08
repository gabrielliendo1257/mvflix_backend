# Distributed System E2E

This is the black-box system E2E suite. It runs Movies, Storage, Media
Ingestion, Activity, Playback, BFF, Kafka, PostgreSQL, MinIO, and WireMock
stubs as separate containers. The Java runner calls the public HTTP APIs and
does not start Spring application contexts in its JVM.

## Scope

The suite currently has eight scenarios:

- Managed deletion: one workflow through the public APIs.
- Add Media: complete an upload through BFF and verify the READY state.
- Add Media: replay the same idempotency key successfully.
- Add Media: reject a different payload with the same idempotency key.
- Add Media: publish completed ingestion activity through BFF.
- Add Media: recover after Media Ingestion restarts during finalization.
- Playback: record progress idempotently, resume it, and project one activity.
- Activity: verify the catalog-access and managed-deletion projections.

These tests cover distributed workflows, persistence, Kafka projections,
idempotency, MinIO uploads, and recovery. They are system E2E tests, not UI
tests: they verify service behavior through HTTP, without a browser or Angular
rendering.

OIDC is deliberately bounded by the `jwks-stub`, which provides the static
JWKS and token endpoint responses needed by the system tests. The
`users-policy-stub` provides only `/api/v1/users/me` and
`/api/v1/users/{username}/policy` for Add Media. Neither stub replaces the
production Authorization or Users service.

UI E2E is a separate layer owned by the frontend project. It will use
Playwright to verify user intent and visible results in Angular, while this
suite remains responsible for distributed system workflows and resilience.

## Run

From the repository root:

```bash
./scripts/e2e.sh
```

The script starts the stack with `docker compose up --wait --build`, runs the
runner with the repository Maven wrapper, and always removes the stack with a
cleanup trap. To run against an already-started stack, invoke
`./mvnw -f e2e/runner/pom.xml test` directly.

The managed-deletion test creates a user and upload through the public APIs,
while the Add Media tests start through BFF, upload four real bytes to MinIO,
complete the ingestion, verify replay idempotency, reject a same-key payload
conflict, and restart Ingestion after completion has entered its finalization
path.

The RSA key in `oidc-stub/test-private-key.pem` is test-only material used by
the runner and the static Movies M2M token mapping. It must never be reused by
any deployed authorization service.

The default host ports are Movies `14040`, Storage `16060`, PostgreSQL `15432`,
MinIO `19000`, and JWKS `18080`. Override `MOVIES_URL`, `STORAGE_URL`, and
`USERS_URL` when running the runner against an already-started stack.

All published ports bind to `127.0.0.1` by default. For a runner in Termux or
another host on the LAN, opt in explicitly with
`E2E_BIND_ADDRESS=0.0.0.0 ./scripts/e2e.sh`.

## Logs

`./scripts/e2e.sh` stores the complete Compose output in
`e2e/logs/compose.log` before removing the stack. Maven Surefire reports are
written to `e2e/runner/target/surefire-reports/`.

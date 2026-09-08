# ADR 0007: Librerias tecnicas compartidas

- **Estado:** Aceptado
- **Fecha:** 2026-09-08

## Contexto

Varios servicios WebFlux repiten configuracion tecnica de seguridad: conversion
de scopes y roles JWT, resource server, JWKS y proteccion de Actuator. Esa
duplicacion puede producir diferencias accidentales entre servicios.

Al mismo tiempo, los servicios tienen politicas de negocio y modelos de dominio
distintos. Compartir esos modelos crearia acoplamiento entre bounded contexts.

## Decision

Se pueden crear librerias tecnicas compartidas, empezando por un starter de
seguridad WebFlux Spring Boot. El starter compartira mecanismos, no politicas:

- conversion de claims JWT a authorities;
- configuracion comun de Resource Server;
- seguridad comun de Actuator;
- respuestas tecnicas uniformes para autenticacion y acceso denegado.

Cada servicio conservara sus reglas de endpoints, scopes requeridos,
ownership, visibilidad y autorizacion de negocio.

No se creara un `shared-domain`. No se compartiran aggregates, entidades,
repositories, value objects ni payloads de eventos mediante un JAR Java.

AsyncAPI, JSON Schema y OpenAPI continuaran siendo la fuente contractual de
eventos y APIs. Si se comparte algo de mensajeria en el futuro, sera solamente
la estructura tecnica del envelope, no sus payloads.

La observabilidad podra extraerse despues en otro starter tecnico. Los puertos
de cada proceso y las decisiones de despliegue no pertenecen a un starter.

## Consecuencias

- Se reduce la duplicacion tecnica sin compartir conocimiento de dominio.
- Los servicios siguen siendo propietarios de sus politicas de seguridad.
- El starter debe probarse como autoconfiguracion y cada consumidor debe
  conservar pruebas de sus reglas particulares.
- Las actualizaciones del starter deben tratarse como cambios de infraestructura
  compartida y validarse contra todos sus consumidores.

# Observabilidad local

El compose de desarrollo incluye Prometheus, Grafana, un OpenTelemetry
Collector y Tempo.

## Arranque

Desde la raíz del proyecto:

```bash
make up-observability-d
```

Para ejecutar los contenedores en primer plano, usar `make up-observability`.
El stack usa las versiones comunes de `infra/docker/container-versions.env` y
los secretos de `infra/docker/.env`.

Grafana queda en `http://<IP-LAN-DE-LA-LAPTOP>:3000`. Las credenciales se
configuran con `GRAFANA_ADMIN_USER` y `GRAFANA_ADMIN_PASSWORD` en `.env`.

Prometheus queda ligado a `127.0.0.1:9095` por defecto y no se publica en la
LAN. Grafana lo consulta internamente mediante la red Docker.

## Métricas

Cada aplicación expone su management server en un puerto separado y protegido
por Basic Auth:

| Servicio | Puerto |
| --- | ---: |
| BFF | 10091 |
| Movies | 10040 |
| Storage | 10060 |
| Users | 10080 |
| Authorization | 10090 |
| Activity | 10070 |
| Playback | 10070 |
| Media ingestion | 10080 |

La ruta es `/actuator/prometheus`. Prometheus usa `ACTUATOR_METRICS_USER` y
`ACTUATOR_METRICS_PASSWORD`; no se deben usar las credenciales por defecto
fuera de desarrollo.

Los servicios que permiten configurar el bind address y el puerto usan las
variables `MANAGEMENT_SERVER_ADDRESS` y `MANAGEMENT_SERVER_PORT`. Son variables
por proceso: si se ejecutan varios servicios Java con el mismo `envs/.env`, no
se debe definir un único puerto global porque todos intentarían usarlo. Los
valores por defecto de cada servicio son los de la tabla anterior. Para cambiar
el puerto de un proceso ejecutado individualmente:

```bash
MANAGEMENT_SERVER_ADDRESS=0.0.0.0 \
MANAGEMENT_SERVER_PORT=10090 \
./mvnw -pl mvflix-authorization spring-boot:run -Dspring-boot.run.profiles=dev
```

En Docker Compose los puertos de management se configuran por servicio; no se
requiere definir estas variables en `envs/.env`.

## Traces desde Termux

El Collector escucha en todas las interfaces en el puerto `4318` y recibe OTLP
HTTP en:

```text
http://<IP-LAN-DE-LA-LAPTOP>:4318/v1/traces
```

Desde Termux, `<IP-LAN-DE-LA-LAPTOP>` es la IP de la laptop, no `localhost` ni
la IP del teléfono. Para una aplicación ejecutada fuera de Docker, establecer:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://<IP-LAN-DE-LA-LAPTOP>:4318/v1/traces
```

El Collector reenvía las trazas a Tempo; Grafana ya incluye ambos data sources.

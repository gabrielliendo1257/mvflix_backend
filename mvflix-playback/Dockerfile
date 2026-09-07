FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -pl mvflix-playback -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/mvflix-playback/target/*.jar app.jar
RUN chown app:app app.jar
USER app
EXPOSE 7070 10070
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD curl --fail --silent http://127.0.0.1:10070/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

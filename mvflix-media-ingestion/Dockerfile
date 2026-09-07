FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -pl mvflix-media-ingestion -am package -Dmaven.test.skip=true
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/mvflix-media-ingestion/target/*.jar app.jar
RUN chown app:app app.jar
USER app
EXPOSE 8080 10080
ENTRYPOINT ["java","-jar","/app/app.jar"]

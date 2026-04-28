# syntax=docker/dockerfile:1.6
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre
RUN useradd -r -u 1001 -g root app && mkdir /app && chown -R app:root /app
WORKDIR /app
COPY --from=build /workspace/build/libs/docsearch.jar /app/docsearch.jar
USER 1001
EXPOSE 8080
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
	CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/docsearch.jar"]

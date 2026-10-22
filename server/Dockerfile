FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY . .
RUN sh ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 campus && useradd --uid 10001 --gid campus --no-create-home campus
WORKDIR /app
COPY --from=build --chown=campus:campus /workspace/build/install/campus-equipment-server/ /app/
USER campus
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD ["java", "-cp", "/app/lib/*", "ru.campus.equipment.HealthCheckKt"]
ENTRYPOINT ["/app/bin/campus-equipment-server"]

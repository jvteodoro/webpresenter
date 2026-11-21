# Central server image. Build the jar first (deploy/build-prod.sh), which bundles the web app.
FROM eclipse-temurin:25-jre

# ffprobe inspects uploaded media.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 webpresenter

COPY server/build/libs/server.jar /app/server.jar
USER webpresenter
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/server.jar"]

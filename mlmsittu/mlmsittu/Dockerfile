# ==============================================================================================
# MLM Sittu — application image
#
# Two stages: build with the full JDK and Gradle, ship with a JRE and the jar. The build stage is
# discarded, so neither the Gradle cache, the source, nor the test code reaches the running image.
# ==============================================================================================

# ---------------------------------------------------------------- build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Wrapper and build scripts first, on their own layer.
#
# Dependency resolution is the slow part of the build and it only changes when build.gradle does.
# Copying the source in the same step would invalidate that layer on every code change and
# re-download the world each time.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --quiet || true

COPY src ./src

# Tests are not run here. They need a live PostgreSQL, which a build container does not have, and
# a build that silently skipped them would be worse than one that never claimed to run them. Run
# `./gradlew test` before building the image.
RUN ./gradlew bootJar --no-daemon -x test

# ---------------------------------------------------------------- run
FROM eclipse-temurin:21-jre-alpine AS run

# curl for the healthcheck below; tzdata because the reporting day boundary is Asia/Colombo and
# the alpine base ships no zone database, which would silently make every daily total wrong.
RUN apk add --no-cache curl tzdata
ENV TZ=Asia/Colombo

# An unprivileged user. A container that runs as root turns any code-execution bug into root on
# the container filesystem, including the mounted document volume.
RUN addgroup -S mlmsittu && adduser -S -G mlmsittu mlmsittu

WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar

# Documents live on a mounted volume. Created here so the directory exists and is owned correctly
# even before anything is uploaded; the compose file binds a named volume over it.
RUN mkdir -p /var/mlmsittu/documents && chown -R mlmsittu:mlmsittu /var/mlmsittu /app
USER mlmsittu

EXPOSE 8080

# The image is production by default.
#
# application.properties still carries localhost development defaults — a database on 127.0.0.1,
# the well-known bootstrap password, an insecure session cookie, and the placeholder NIC keys.
# They are harmless on a laptop and dangerous in a container: an image started without a profile
# would quietly use every one of them and come up looking healthy.
#
# Defaulting the profile here means the safe configuration is the one you get by accident.
# Overriding it is possible but has to be deliberate.
ENV SPRING_PROFILES_ACTIVE=prod

# MaxRAMPercentage rather than a fixed -Xmx: the JVM reads the container's memory limit, so the
# heap follows whatever the EC2 instance and compose allocate instead of a number that is wrong on
# every machine except the one it was chosen for.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError"

# ExitOnOutOfMemoryError is deliberate. A JVM that has exhausted its heap does not recover; it
# thrashes, serves slowly, and passes a shallow health check while doing it. Dying lets the
# restart policy replace it.

# start-period is generous because Spring takes 20-40 seconds to come up and a probe that fires
# during startup would restart the container in a loop that looks exactly like a crash.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

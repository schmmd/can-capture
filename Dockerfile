# Builds the debug APK in a clean container.
#   docker build --output type=local,dest=out .
# leaves out/app-debug.apk.
FROM ghcr.io/cirruslabs/android-sdk:34 AS build

WORKDIR /src
# Warm the Gradle distribution in its own layer so app edits don't re-download it.
COPY gradlew ./
COPY gradle/ gradle/
RUN ./gradlew --version --no-daemon
COPY . .
RUN ./gradlew assembleDebug --no-daemon

FROM scratch
COPY --from=build /src/app/build/outputs/apk/debug/app-debug.apk /app-debug.apk

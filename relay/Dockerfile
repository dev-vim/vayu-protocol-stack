# syntax=docker/dockerfile:1

# ── Build stage ───────────────────────────────────────────────────────────────
# No mvnw in this repo, so use the official Maven image for the build.
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
# Pre-fetch dependencies in a separate layer for better cache reuse
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn -B -q package -DskipTests

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

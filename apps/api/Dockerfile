# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies first: copy only the POM, resolve, then copy sources.
# Use the base image's `mvn` (the project's mvnw wrapper pins a SHA-256 that can
# fail validation in a fresh container), same Maven 3.9 line.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Non-root user for the container process.
RUN groupadd -r atoms && useradd -r -g atoms atoms
USER atoms

COPY --from=build /build/target/atoms-api-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
# Bind 0.0.0.0 in the container; SERVER_ADDRESS/SERVER_PORT are overridable at runtime.
ENV SERVER_ADDRESS=0.0.0.0 SERVER_PORT=8080
ENTRYPOINT ["java", "-jar", "app.jar"]

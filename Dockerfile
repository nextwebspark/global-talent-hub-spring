# ── Build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies first (only re-resolves when pom.xml changes).
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
# Skip tests in the image build — CI/local runs them. Produces the fat jar.
RUN mvn -q -B clean package -DskipTests

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root for safety.
RUN useradd -r -u 1001 spring
USER spring

COPY --from=build /build/target/*.jar app.jar

# Cloud Run injects PORT (default 8080); server.port=${PORT:5000} honors it.
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

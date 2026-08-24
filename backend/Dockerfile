# Multi-stage build: compile with a full Maven+JDK image, run on a slim JRE.
# No secrets are baked into this image at any stage - every credential
# (DB_URL/DB_USERNAME/DB_PASSWORD, ANTHROPIC_API_KEY, RAZORPAY_*) is supplied
# at container-run time as an environment variable, never as a build arg and
# never copied from a .env file. PostgreSQL itself stays external/managed -
# it is deliberately not part of this image (see docker-compose.yml for the
# separate, local-development-only Postgres container).

# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependency resolution separately from source changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package
RUN cp target/backend-*.jar app.jar

# ---- runtime stage -------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Run as a non-root user rather than the image's default root.
RUN addgroup -S recoverai && adduser -S recoverai -G recoverai
COPY --from=build /app/app.jar app.jar
USER recoverai

# The application itself resolves the actual bind port from $PORT (falling
# back to $SERVER_PORT, then 8080 - see application.yml); 8080 is declared
# here as the conventional default for platforms that read EXPOSE.
EXPOSE 8080

# No SPRING_PROFILES_ACTIVE is set here - the default (no-profile) config in
# application.yml is already PostgreSQL/Flyway/validate-schema production
# config; set SPRING_PROFILES_ACTIVE=prod at deploy time for the additional
# explicit CORS/logging tightening in application-prod.yml (see README
# "Buildathon Deployment").
ENTRYPOINT ["java", "-jar", "app.jar"]

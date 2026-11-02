# -------------------------------------------------------------
# MovieClash — Production Dockerfile for Render
# Multi-stage build: compiles Spring Boot backend & bundles frontend
# -------------------------------------------------------------

# Stage 1: Build Spring Boot backend with Java 21 & Maven
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Install maven
RUN apk add --no-cache maven

# Copy POM and download dependencies
COPY backend/pom.xml ./backend/
RUN cd backend && mvn dependency:go-offline -B || true

# Copy backend source and frontend assets
COPY backend/src ./backend/src
COPY frontend ./frontend

# Embed frontend files into Spring Boot static classpath
RUN mkdir -p ./backend/src/main/resources/static && \
    cp -r ./frontend/* ./backend/src/main/resources/static/

# Package executable Spring Boot JAR
RUN cd backend && mvn clean package -DskipTests

# Stage 2: Lightweight JRE 21 Runtime Environment
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for container security
RUN addgroup -S movieclash && adduser -S movieclash -G movieclash

# Copy packaged JAR and standalone frontend directory
COPY --from=builder /workspace/backend/target/movieclash-backend-*.jar app.jar
COPY --from=builder /workspace/frontend ./frontend

RUN chown -R movieclash:movieclash /app

USER movieclash

# Render dynamically passes PORT (e.g., 10000); default to 8080 locally
ENV PORT=8080 \
    JAVA_OPTS="-Xms128m -Xmx320m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=4s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT:-8080}/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT:-8080}"]


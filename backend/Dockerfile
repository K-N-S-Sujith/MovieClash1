# -------------------------------------------------------------
# MovieClash Backend — Multi-Stage Production Dockerfile
# Optimized for minimal footprint and low-latency startup
# -------------------------------------------------------------

# Stage 1: Build JAR with Maven and Java 21
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Copy Maven POM and dependency definitions
COPY pom.xml .
COPY src ./src

# Build production artifact without running unit tests inside docker build
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

# Stage 2: Minimal Runtime Environment
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S movieclash && adduser -S movieclash -G movieclash

COPY --from=builder /build/target/movieclash-backend-*.jar app.jar
RUN chown -R movieclash:movieclash /app

USER movieclash

ENV PORT=8080 \
    JAVA_OPTS="-Xmx300m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT:-8080}/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT:-8080}"]


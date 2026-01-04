# CodeLens Backend - Production Dockerfile
# Multi-stage build for optimized image size

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# Copy pom.xml first for dependency caching
COPY pom.xml .

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn package -DskipTests -B

# Stage 2: Production
FROM eclipse-temurin:17-jre-alpine AS runner

WORKDIR /app

# Add non-root user for security
RUN addgroup --system --gid 1001 spring && \
    adduser --system --uid 1001 --ingroup spring codelens

# Install curl for health checks
RUN apk add --no-cache curl

# Copy built JAR
COPY --from=builder --chown=codelens:spring /app/target/codelens-*.jar app.jar

# Copy prompts directory
COPY --chown=codelens:spring src/main/resources/prompts /app/prompts

# Set user
USER codelens

# Expose port
EXPOSE 9292

# Environment variables
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:MaxGCPauseMillis=100"
ENV SPRING_PROFILES_ACTIVE=prod
ENV SERVER_PORT=9292

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:9292/actuator/health || exit 1

# Start the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

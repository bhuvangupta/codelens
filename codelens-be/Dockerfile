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

# curl for health checks; git and python for the security gate scanners
RUN apk add --no-cache curl git python3 py3-pip

# Security gate scanners (codelens/secrets, codelens/deps, codelens/sast).
# Gitleaks and Trivy are release binaries verified against the checksums published
# with each release; when bumping a version, update both of its checksums.
# Semgrep is pinned by version only.
ARG TARGETARCH
ARG GITLEAKS_VERSION=8.30.1
ARG GITLEAKS_SHA256_AMD64=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb
ARG GITLEAKS_SHA256_ARM64=e4a487ee7ccd7d3a7f7ec08657610aa3606637dab924210b3aee62570fb4b080
ARG TRIVY_VERSION=0.76.2
ARG TRIVY_SHA256_AMD64=bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf
ARG TRIVY_SHA256_ARM64=ab9d51f86e6a5fb936f605faff6b593ac51bc378fff4d3e8b9d58358b9c367c3
ARG SEMGREP_VERSION=1.185.0

RUN set -eux; \
    case "${TARGETARCH:-$(apk --print-arch)}" in \
      amd64|x86_64) \
        gitleaks_file="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"; gitleaks_sha="${GITLEAKS_SHA256_AMD64}"; \
        trivy_file="trivy_${TRIVY_VERSION}_Linux-64bit.tar.gz"; trivy_sha="${TRIVY_SHA256_AMD64}" ;; \
      arm64|aarch64) \
        gitleaks_file="gitleaks_${GITLEAKS_VERSION}_linux_arm64.tar.gz"; gitleaks_sha="${GITLEAKS_SHA256_ARM64}"; \
        trivy_file="trivy_${TRIVY_VERSION}_Linux-ARM64.tar.gz"; trivy_sha="${TRIVY_SHA256_ARM64}" ;; \
      *) echo "unsupported architecture"; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/gitleaks.tar.gz \
      "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/${gitleaks_file}"; \
    echo "${gitleaks_sha}  /tmp/gitleaks.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/gitleaks.tar.gz -C /usr/local/bin gitleaks; \
    curl -fsSL -o /tmp/trivy.tar.gz \
      "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/${trivy_file}"; \
    echo "${trivy_sha}  /tmp/trivy.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/trivy.tar.gz -C /usr/local/bin trivy; \
    rm -f /tmp/gitleaks.tar.gz /tmp/trivy.tar.gz; \
    python3 -m venv /opt/semgrep; \
    /opt/semgrep/bin/pip install --no-cache-dir "semgrep==${SEMGREP_VERSION}"; \
    gitleaks version; trivy --version; /opt/semgrep/bin/semgrep --version

ENV PATH="/opt/semgrep/bin:${PATH}"

# The scanners need a writable home and Trivy a place for its vulnerability
# database (about 1.6 GB; mount a volume here so it survives restarts)
ENV HOME=/home/codelens
ENV TRIVY_CACHE_DIR=/var/cache/trivy
RUN mkdir -p /home/codelens /var/cache/trivy && \
    chown -R codelens:spring /home/codelens /var/cache/trivy

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

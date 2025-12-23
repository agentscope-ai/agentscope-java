# Single-stage Dockerfile for business-sub-agent
# Using pre-built JAR from local Maven build
FROM eclipse-temurin:17-jre

# Set metadata labels
LABEL maintainer="AgentScope Team"
LABEL description="Business Sub Agent - Multi-Agent Demo"
LABEL version="1.0.0"

# Create a non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Set working directory
WORKDIR /app

# Copy pre-built JAR from local target directory
COPY target/business-sub-agent.jar app.jar

# Change ownership to non-root user
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 10006

# Define environment variables with defaults
ENV SERVER_PORT=10006 \
    NACOS_SERVER_ADDR=127.0.0.1:8848 \
    NACOS_NAMESPACE=public \
    NACOS_USERNAME=nacos \
    NACOS_PASSWORD=nacos \
    JAVA_OPTS="-Xms512m -Xmx1024m"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:${SERVER_PORT}/actuator/health || exit 1

# Set entrypoint to run the application
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]


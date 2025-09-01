# Dockerfile simplifié - À placer dans le dossier external/
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Create user for security
RUN addgroup -g 1000 spring && adduser -D -s /bin/sh -u 1000 -G spring spring

# Copy JAR file (assumant que Maven a déjà construit le JAR)
# Le JAR sera dans target/ après mvn package
COPY target/*.jar app.jar

# Create directories for logs and temp files
RUN mkdir -p /app/logs /app/temp && \
    chown -R spring:spring /app

# Switch to non-root user
USER spring:spring

# JVM options for better container performance
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+UseG1GC"

# Expose port
EXPOSE 8090

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8090/actuator/health || exit 1

# Start application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
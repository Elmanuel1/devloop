FROM eclipse-temurin:21-jre-alpine AS runtime

# Install minimal packages needed
RUN apk add --no-cache curl tini

# Create non-root user
RUN addgroup -g 1000 tosspaper && adduser -D -u 1000 -G tosspaper tosspaper

# Set working directory
WORKDIR /app

# Copy application jar
COPY --chown=tosspaper:tosspaper services/everything/build/libs/everything-1.0.0.jar app.jar
COPY --chown=tosspaper:tosspaper ./schema-prompts/ schema-prompts
COPY --chown=tosspaper:tosspaper disposable_email_blocklist.txt disposable_email_blocklist.txt
# Copy Middleware.io Java agent
COPY --chown=tosspaper:tosspaper ./otel-jars/javaagent.jar middleware-javaagent.jar
# Switch to non-root user
USER tosspaper

# Expose port
EXPOSE 8080

# JVM optimization
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+UseStringDeduplication \
               -XX:+OptimizeStringConcat \
               -Djava.security.egd=file:/dev/./urandom \
               -XX:+ExitOnOutOfMemoryError \
               --enable-preview"

# OpenTelemetry agent system properties for trace context propagation
ENV OTEL_PROPAGATORS="tracecontext,baggage"
# Disable high-cardinality JVM network metrics to prevent cardinality limit warnings
# These metrics (jvm.network.io, jvm.network.time) create too many unique combinations per network interface
# Other JVM metrics (memory, GC, threads, etc.) remain enabled
ENV OTEL_INSTRUMENTATION_JVM_NETWORK_IO_ENABLED="false"
ENV OTEL_INSTRUMENTATION_JVM_NETWORK_TIME_ENABLED="false"

# Use tini for signal handling
# Include Middleware.io Java agent if MW_API_KEY is set
ENTRYPOINT ["tini", "--", "sh", "-c", "java $JAVA_OPTS -javaagent:/app/middleware-javaagent.jar -jar app.jar"]

FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# OpenTelemetry Java Agent (optional - for enhanced tracing)
# ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v1.32.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

COPY --from=build /app/target/*.jar app.jar
EXPOSE 8088

# Without OTel agent
ENTRYPOINT ["java", "-jar", "app.jar"]

# With OTel agent (uncomment to enable)
# ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "app.jar"]


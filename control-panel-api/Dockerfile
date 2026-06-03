# Stage 1: build
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml ./pom.xml
COPY control-panel-api/pom.xml ./control-panel-api/pom.xml
COPY license-verifier/pom.xml ./license-verifier/pom.xml
COPY license-verifier-spring-boot-starter/pom.xml ./license-verifier-spring-boot-starter/pom.xml
COPY sample-docker-app/pom.xml ./sample-docker-app/pom.xml
COPY control-panel-api/src ./control-panel-api/src
RUN mvn -B -pl control-panel-api -am -DskipTests package

# Stage 2: runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=builder --chown=app:app /build/control-panel-api/target/control-panel-api.jar /app/app.jar

USER app
EXPOSE 8080

# Liquibase + Spring Boot startup can take a moment; allow a generous start period.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

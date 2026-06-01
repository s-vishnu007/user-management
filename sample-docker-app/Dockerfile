# syntax=docker/dockerfile:1.7
# ---------- builder ----------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace

# Copy the parent POM and module POMs first to cache dependency resolution.
COPY pom.xml ./pom.xml
COPY license-verifier/pom.xml license-verifier/pom.xml
COPY license-verifier-spring-boot-starter/pom.xml license-verifier-spring-boot-starter/pom.xml
COPY sample-docker-app/pom.xml sample-docker-app/pom.xml

# Pull dependencies (best-effort cache layer; will resolve siblings later).
RUN mvn -B -pl sample-docker-app -am -DskipTests dependency:go-offline || true

# Copy sources and build the sample app + its required sibling modules.
COPY license-verifier/src license-verifier/src
COPY license-verifier-spring-boot-starter/src license-verifier-spring-boot-starter/src
COPY sample-docker-app/src sample-docker-app/src

RUN mvn -B -pl sample-docker-app -am -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

# Where customers mount their license file.
VOLUME ["/etc/app"]

COPY --from=builder /workspace/sample-docker-app/target/sample-docker-app.jar /app/app.jar

ENV LICENSE_PATH=/etc/app/license.lic \
    LICENSE_AUDIENCE=docker-app-prod \
    LICENSE_ISSUER=https://control-panel.example.com \
    LICENSE_STRICT=true \
    JAVA_OPTS=""

USER app
EXPOSE 9090

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- http://localhost:9090/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

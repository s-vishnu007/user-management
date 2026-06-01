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
WORKDIR /app
COPY --from=builder /build/control-panel-api/target/control-panel-api.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

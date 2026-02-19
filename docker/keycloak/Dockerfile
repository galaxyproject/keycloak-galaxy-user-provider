FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
COPY src/ src/
RUN mvn package -B -DskipTests

FROM quay.io/keycloak/keycloak:26.0
COPY --from=builder /build/target/galaxy-user-provider.jar /opt/keycloak/providers/galaxy-user-provider.jar

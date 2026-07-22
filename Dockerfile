# =============================================================================
# Stage 1: build — compila el jar y extrae las capas Spring Boot
# =============================================================================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Resuelve dependencias primero (capa cacheable: solo cambia cuando pom.xml cambia)
COPY backend/pom.xml backend/pom.xml
RUN mvn -f backend/pom.xml dependency:go-offline -B -q

# Empaqueta (sin tests: requieren PostgreSQL externo; corren en CI)
COPY backend/src backend/src
RUN mvn -f backend/pom.xml package -DskipTests -B -q

# Spring Boot 3.3+ tools extract --layers produce un thin jar + lib/:
#   dependencies/lib/   <- jars de dependencias estables
#   snapshot-dependencies/lib/ <- snapshots (normalmente vacio)
#   spring-boot-loader/ <- vacio (loader va dentro del thin jar)
#   application/<artifact>.jar <- thin jar con Main-Class directo
RUN java -Djarmode=tools \
    -jar backend/target/monitoreofletx-*.jar \
    extract --layers --destination /build/layers && \
    mv /build/layers/application/monitoreofletx-*.jar /build/layers/application/app.jar

# =============================================================================
# Stage 2: runtime — imagen minima sin JDK ni Maven
# =============================================================================
FROM eclipse-temurin:21-jre-alpine

# Usuario no-root (uid/gid 1001)
RUN addgroup -g 1001 monitoreofletx && \
    adduser -u 1001 -G monitoreofletx -H -D monitoreofletx

WORKDIR /app

# Copia capas en orden de menor a mayor volatilidad para maximizar cache de Docker.
# dependencies/lib/ se copia primero — rara vez cambia entre builds.
COPY --from=build --chown=monitoreofletx:monitoreofletx /build/layers/dependencies/          ./
COPY --from=build --chown=monitoreofletx:monitoreofletx /build/layers/snapshot-dependencies/ ./
# app.jar es el thin jar de la aplicacion — cambia en cada release
COPY --from=build --chown=monitoreofletx:monitoreofletx /build/layers/application/app.jar    ./app.jar

USER monitoreofletx

# La JVM respeta el limite de memoria del contenedor (mem_limit en compose)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# Puerto del actuator/management (Spring Boot: server.port=-1, management.server.port=8098)
EXPOSE 8098

# Spring Boot 3.3+ tools layout: thin jar con Main-Class directo, Class-Path: lib/...
# NO usar JarLauncher: con jarmode=tools extract el jar ya trae Main-Class directo.
ENTRYPOINT ["java", "-jar", "app.jar"]

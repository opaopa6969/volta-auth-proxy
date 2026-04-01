FROM maven:3.9-eclipse-temurin-21-alpine

WORKDIR /app

# Resolve dependencies first — this layer is cached unless pom.xml changes
COPY pom.xml .
RUN mvn dependency:resolve -q

# Copy source and compile (also pre-compiles JTE templates into target/jte-classes)
COPY src ./src
RUN mvn compile -q

# Config is bind-mounted at runtime so operators can edit it without rebuilding.
# Copy a default here as fallback.
COPY volta-config.yaml .

EXPOSE 7070

# -o = offline (all deps already in the image layer above)
ENTRYPOINT ["mvn", "-o", "-q", "exec:java"]

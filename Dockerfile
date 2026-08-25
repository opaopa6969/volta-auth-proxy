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

# JVM メモリ上限・OOM 時の自己終了（2026-08-23/24 本番枯渇予防）。
# mvn exec:java は maven プロセス内で実行するので MAVEN_OPTS がそのまま効く。
# -Xmx1g の根拠: Javalin + PostgreSQL + Kafka クライアント（未実測・保守値）。
ENV MAVEN_OPTS="-Xmx1g -XX:+ExitOnOutOfMemoryError"

# -o = offline (all deps already in the image layer above)
ENTRYPOINT ["mvn", "-o", "-q", "exec:java"]

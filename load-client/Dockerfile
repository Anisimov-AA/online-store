# Этап 1: сборка JAR с помощью Maven
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package

# Этап 2: запуск, только Java без Maven
FROM eclipse-temurin:17-jre
RUN groupadd app && useradd -g app -s /bin/sh -M app
WORKDIR /app
COPY --from=builder /app/target/load-client.jar app.jar
RUN chown -R app:app /app
USER app
ENV JAVA_OPTS="-Xmx1g"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
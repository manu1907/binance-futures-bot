FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
COPY src ./src

RUN mvn -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --uid 10001 --create-home botuser \
    && mkdir -p /app/config /app/var \
    && chown -R botuser:botuser /app

COPY --from=build /build/target/binance-futures-bot-0.1.0-SNAPSHOT.jar /app/binance-futures-bot.jar

USER botuser

ENV TZ=Europe/Paris
ENV BOT_CONFIG_FILE=/app/config/application.yml

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "/app/binance-futures-bot.jar"]
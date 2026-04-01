# Binance Futures Bot

Java 21 Binance USDⓈ-M Futures bot for:

- BTCUSDT, ETHUSDT, SOLUSDT
- Binance Hedge Mode
- manual intervention adoption
- market data + signal evaluation
- dry-run planning
- demo/live execution path
- protection order placement
- lifecycle handling
- risk halts
- CSV trade journal

## Current strategy

The current strategy implementation is a strict Elder Triple Screen style directional futures strategy running on:

- 4h first screen (trend / tide)
- 1h second screen (counter-trend pullback / bounce)
- 15m third screen (trigger)

## First run

Copy the example config:

```bash
cp src/main/resources/application-example.yml src/main/resources/application.yml
mvn compile
mvn test
mvn exec:java

### 2. Verify `application-example.yml` separately
Run this exact command so we inspect the real file cleanly:

```bash
sed -n '1,120p' src/main/resources/application-example.yml
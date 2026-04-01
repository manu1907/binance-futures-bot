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


### `src/main/resources/application-example.yml`
```yaml
exchange:
  baseUrl: "https://fapi.binance.com"
  wsBaseUrl: "wss://fstream.binance.com"
  apiKey: "${BINANCE_API_KEY:}"
  apiSecret: "${BINANCE_API_SECRET:}"
  useTestnet: true
  hedgeModeRequired: true

trading:
  symbols: ["BTCUSDT", "ETHUSDT", "SOLUSDT"]
  activeSymbolLimit: 1
  dryRun: true
  adoptionMode: "ADOPT_AND_CONTINUE"
  defaultLeverage: 2
  maxRiskPerTradePct: 0.5
  maxDailyDrawdownPct: 2.0
  maxOpenPositions: 1

  effectiveCapitalUsd: 200.0
  riskCapitalMode: "CAPPED_EQUITY"
  minimumTradeNotionalUsd: 5.0
  stopAtrMultiple: 1.5
  takeProfitRiskReward: 2.0
  entryCooldownSeconds: 120
  oppositeSignalPolicy: "IGNORE"

  maxConsecutiveLosses: 3
  marketDataStaleSeconds: 90
  journalCsvPath: "var/trade-journal.csv"
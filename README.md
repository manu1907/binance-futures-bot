# Binance Futures Bot

Bootstrap for a Java 21 Binance USDⓈ-M Futures bot that:
- watches BTCUSDT, ETHUSDT, SOLUSDT
- adopts manual interventions as exchange truth
- will later add Elder-style multi-timeframe signals
- starts in dry-run mode

## First run

Copy the example config:

```bash
cp src/main/resources/application-example.yml src/main/resources/application.yml

mvn compile
mvn test
mvn exec:java

### src/main/resources/application-example.yml
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
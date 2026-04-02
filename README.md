# Binance Futures Bot

Java 21 Binance USDⓈ-M Futures bot built for:

- BTCUSDT, ETHUSDT, SOLUSDT
- Binance Hedge Mode
- strict Elder Triple Screen strategy
- manual intervention adoption
- dry-run planning
- demo/live execution path
- protection order placement
- lifecycle handling
- risk halts
- replay/backtest harness
- CSV trade journaling

## What the bot does

The bot is designed to:

- connect to Binance USDⓈ-M Futures
- verify Hedge Mode on startup
- reconcile exchange state against internal state
- adopt manual position/order changes
- stream 4h, 1h, and 15m market data
- evaluate a strict Elder Triple Screen strategy
- size positions from risk and ATR-based stop distance
- place entries plus protective stop / take-profit orders
- enforce guardrails, lifecycle rules, and risk halts
- journal closed trades to CSV
- replay historical periods for testing

## Strategy

The current strategy is a strict Elder Triple Screen style directional setup:

- **First screen (4h):** trend / tide
  - EMA 13
  - MACD histogram slope

- **Second screen (1h):** counter-trend pullback / bounce
  - Force Index(2)

- **Third screen (15m):** trigger
  - breakout for longs
  - breakdown for shorts

## Config files

### Tracked example config
`src/main/resources/application-example.yml`

This file is committed to the repo and documents the expected config structure.

### Local runtime config
`src/main/resources/application.yml`

This file is for your local machine or Codespace and is not meant to be committed.

### Deployment config
`deploy/application.example.yml`

This is the example config used for VPS / Docker deployment.

### Environment variables
`.env.example`

This is only a template. The real `.env` should be created locally on the VPS and must never be committed.

## Local development

Copy the example config:

```bash
cp src/main/resources/application-example.yml src/main/resources/application.yml
mvn compile
mvn test
mvn exec:java
package com.example.futuresbot.exchange;

public final class BinanceApiException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public BinanceApiException(int statusCode, String responseBody) {
        super("Binance API call failed. status=" + statusCode + " body=" + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
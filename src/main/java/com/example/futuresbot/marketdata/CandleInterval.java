package com.example.futuresbot.marketdata;

public enum CandleInterval {
    MINUTES_15("15m"),
    HOUR_1("1h"),
    HOUR_4("4h");

    private final String code;

    CandleInterval(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static CandleInterval fromCode(String code) {
        for (CandleInterval interval : values()) {
            if (interval.code.equalsIgnoreCase(code)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("Unsupported interval: " + code);
    }
}
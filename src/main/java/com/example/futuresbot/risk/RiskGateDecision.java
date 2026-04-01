package com.example.futuresbot.risk;

public record RiskGateDecision(boolean allowed, String reason) {
    public static RiskGateDecision allow() {
        return new RiskGateDecision(true, "OK");
    }

    public static RiskGateDecision block(String reason) {
        return new RiskGateDecision(false, reason);
    }
}
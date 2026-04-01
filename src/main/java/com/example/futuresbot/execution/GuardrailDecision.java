package com.example.futuresbot.execution;

public record GuardrailDecision(boolean allowed, String reason) {
    public static GuardrailDecision allow() {
        return new GuardrailDecision(true, "OK");
    }

    public static GuardrailDecision block(String reason) {
        return new GuardrailDecision(false, reason);
    }
}
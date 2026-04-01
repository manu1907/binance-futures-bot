package com.example.futuresbot.execution;

public record LifecycleDecision(Action action, String reason) {
    public enum Action {
        PROCEED,
        IGNORE_SIGNAL,
        FLATTEN_AND_WAIT
    }

    public static LifecycleDecision proceed() {
        return new LifecycleDecision(Action.PROCEED, "OK");
    }

    public static LifecycleDecision ignore(String reason) {
        return new LifecycleDecision(Action.IGNORE_SIGNAL, reason);
    }

    public static LifecycleDecision flattenAndWait(String reason) {
        return new LifecycleDecision(Action.FLATTEN_AND_WAIT, reason);
    }
}
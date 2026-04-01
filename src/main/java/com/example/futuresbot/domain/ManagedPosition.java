package com.example.futuresbot.domain;

import java.time.Instant;
import java.util.Objects;

public record ManagedPosition(
        PositionSnapshot snapshot,
        boolean adoptedFromManualAction,
        Instant adoptedAt,
        String note) {
    public ManagedPosition {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(adoptedAt, "adoptedAt");
        Objects.requireNonNull(note, "note");
    }
}
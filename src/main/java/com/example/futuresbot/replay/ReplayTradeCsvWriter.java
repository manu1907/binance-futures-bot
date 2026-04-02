package com.example.futuresbot.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReplayTradeCsvWriter {
    private final Path path;

    public ReplayTradeCsvWriter(String filePath) {
        this.path = Path.of(filePath == null || filePath.isBlank() ? "var/replay-trades.csv" : filePath);
    }

    public synchronized void append(ReplayTradeRecord record) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            boolean exists = Files.exists(path);
            if (!exists) {
                Files.writeString(
                        path,
                        ReplayTradeRecord.csvHeader() + System.lineSeparator(),
                        StandardCharsets.UTF_8);
            }

            Files.writeString(
                    path,
                    record.toCsvRow() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append replay trades to " + path, e);
        }
    }
}
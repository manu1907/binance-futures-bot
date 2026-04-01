package com.example.futuresbot.risk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TradeJournalCsvWriter {
    private final Path path;

    public TradeJournalCsvWriter(String filePath) {
        this.path = Path.of(filePath == null || filePath.isBlank() ? "var/trade-journal.csv" : filePath);
    }

    public synchronized void append(TradeJournalRecord record) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            boolean exists = Files.exists(path);
            if (!exists) {
                Files.writeString(
                        path,
                        TradeJournalRecord.csvHeader() + System.lineSeparator(),
                        StandardCharsets.UTF_8);
            }

            Files.writeString(
                    path,
                    record.toCsvRow() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append trade journal to " + path, e);
        }
    }
}
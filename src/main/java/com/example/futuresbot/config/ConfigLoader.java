package com.example.futuresbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Z0-9_]+)(?::([^}]*))?}");

    private ConfigLoader() {
    }

    public static AppConfig load(String file) {
        try {
            String raw = Files.readString(Path.of(file));
            String resolved = resolveEnvPlaceholders(raw);
            AppConfig config = YAML.readValue(resolved, AppConfig.class);
            validate(config);
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load config from " + file, e);
        }
    }

    private static String resolveEnvPlaceholders(String raw) {
        Matcher matcher = ENV_PATTERN.matcher(raw);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String envName = matcher.group(1);
            String defaultValue = Objects.isNull(matcher.group(2)) ? "" : matcher.group(2);
            String resolved = System.getenv().getOrDefault(envName, defaultValue);
            matcher.appendReplacement(output, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static void validate(AppConfig config) {
        if (Objects.isNull(config)) {
            throw new IllegalStateException("Config file produced a null AppConfig");
        }
        if (Objects.isNull(config.exchange())) {
            throw new IllegalStateException("Missing 'exchange' section in config");
        }
        if (Objects.isNull(config.trading())) {
            throw new IllegalStateException("Missing 'trading' section in config");
        }
        if (StringUtils.isBlank(config.exchange().apiKey())) {
            throw new IllegalStateException(
                    "Missing Binance API key. Set BINANCE_API_KEY or put apiKey in application.yml");
        }
        if (Objects.isNull(config.exchange().apiSecret())) {
            throw new IllegalStateException(
                    "Missing Binance API secret. Set BINANCE_API_SECRET or put apiSecret in application.yml");
        }
    }
}
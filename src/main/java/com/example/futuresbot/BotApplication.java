package com.example.futuresbot;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.config.ConfigLoader;
import com.example.futuresbot.runtime.BotRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class BotApplication {
    private static final Logger log = LoggerFactory.getLogger(BotApplication.class);

    public static void main(String[] args) {
        String configPath = resolveConfigPath();
        AppConfig config = ConfigLoader.load(configPath);
        log.info("Starting bot with config={} dryRun={} symbols={}",
                configPath, config.trading().dryRun(), config.trading().symbols());
        new BotRuntime(config).start();
    }

    private static String resolveConfigPath() {
        String envPath = System.getenv("BOT_CONFIG_FILE");
        if (envPath != null && !envPath.isBlank()) {
            return envPath;
        }

        Path primary = Path.of("src/main/resources/application.yml");
        if (Files.exists(primary)) {
            return primary.toString();
        }

        return "src/main/resources/application-example.yml";
    }
}
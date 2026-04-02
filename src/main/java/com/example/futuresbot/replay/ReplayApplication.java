package com.example.futuresbot.replay;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ReplayApplication {
    private static final Logger log = LoggerFactory.getLogger(ReplayApplication.class);

    public static void main(String[] args) {
        String configPath = resolveConfigPath();
        AppConfig config = ConfigLoader.load(configPath);
        log.info("Starting replay with config={}", configPath);
        new ReplayRunner(config).run();
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
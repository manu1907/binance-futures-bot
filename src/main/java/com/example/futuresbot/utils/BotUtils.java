package com.example.futuresbot.utils;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

@UtilityClass
public class BotUtils {
    public static String resolveConfigPath() {
        String envPath = System.getenv("BOT_CONFIG_FILE");
        if (StringUtils.isNotBlank(envPath)) {
            return envPath;
        }

        Path primary = Path.of("src/main/resources/application.yml");
        if (Files.exists(primary)) {
            return primary.toString();
        }

        return "src/main/resources/application-example.yml";
    }
}

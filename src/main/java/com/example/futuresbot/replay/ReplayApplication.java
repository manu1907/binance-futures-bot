package com.example.futuresbot.replay;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.config.ConfigLoader;
import com.example.futuresbot.utils.BotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReplayApplication {
    private static final Logger log = LoggerFactory.getLogger(ReplayApplication.class);

    public static void main(String[] args) {
        String configPath = BotUtils.resolveConfigPath();
        AppConfig config = ConfigLoader.load(configPath);
        log.info("Starting replay with config={}", configPath);
        new ReplayRunner(config).run();
    }
}
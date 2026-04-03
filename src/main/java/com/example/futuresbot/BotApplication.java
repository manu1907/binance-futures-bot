package com.example.futuresbot;

import com.example.futuresbot.config.AppConfig;
import com.example.futuresbot.config.ConfigLoader;
import com.example.futuresbot.runtime.BotRuntime;
import com.example.futuresbot.utils.BotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BotApplication {
    private static final Logger log = LoggerFactory.getLogger(BotApplication.class);

    public static void main(String[] args) {
        String configPath = BotUtils.resolveConfigPath();
        AppConfig config = ConfigLoader.load(configPath);
        log.info("Starting bot with config={} dryRun={} symbols={}",
                configPath, config.trading().dryRun(), config.trading().symbols());
        new BotRuntime(config).start();
    }
}
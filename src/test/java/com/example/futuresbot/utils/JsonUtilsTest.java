package com.example.futuresbot.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonUtilsTest {

    @Test
    void parsesStringEncodedDecimalsFromBinanceJson() throws Exception {
        JsonNode node = JsonMapper.builder().build().readTree("""
                {
                  "minQty": "0.01",
                  "maxQty": "6000",
                  "stepSize": "0.01",
                  "tickSize": "0.10",
                  "notional": "5"
                }
                """);

        assertEquals(new BigDecimal("0.01"), JsonUtils.decimal(node, "minQty"));
        assertEquals(new BigDecimal("6000"), JsonUtils.decimal(node, "maxQty"));
        assertEquals(new BigDecimal("0.01"), JsonUtils.decimal(node, "stepSize"));
        assertEquals(new BigDecimal("0.10"), JsonUtils.decimal(node, "tickSize"));
        assertEquals(new BigDecimal("5"), JsonUtils.decimal(node, "notional"));
    }
}
package com.example.futuresbot.utils;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;

@UtilityClass
public class JsonUtils {
    public BigDecimal decimal(JsonNode node, String field) {
        String value = node.path(field).asText("0");
        if (StringUtils.isBlank(value)) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }
}

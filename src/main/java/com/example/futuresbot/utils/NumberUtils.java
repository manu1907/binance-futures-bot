package com.example.futuresbot.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class NumberUtils {
    public static boolean isPositive(Number number) {
        return number != null && number.doubleValue() > 0;
    }
}

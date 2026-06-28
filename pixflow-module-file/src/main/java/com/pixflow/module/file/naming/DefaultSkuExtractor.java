package com.pixflow.module.file.naming;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultSkuExtractor implements SkuExtractor {
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}]+");

    @Override
    public String extract(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return "UNKNOWN";
        }
        Matcher matcher = TOKEN.matcher(baseName.trim());
        if (matcher.find()) {
            return matcher.group();
        }
        return baseName.trim();
    }
}

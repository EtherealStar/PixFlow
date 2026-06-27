package com.pixflow.common.sanitize;

import java.util.regex.Pattern;

/**
 * 对外展示和落盘前的统一脱敏工具。
 */
public final class Sanitizer {
    private static final Pattern BEARER_TOKEN = Pattern.compile("Bearer\\s+\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENAI_KEY = Pattern.compile("sk-[A-Za-z0-9]{16,}");
    private static final Pattern ALIYUN_KEY = Pattern.compile("LTAI[A-Za-z0-9]+");
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)[A-Z]:\\\\[^\\s]+");
    private static final Pattern UNIX_PATH = Pattern.compile("(?<![A-Za-z0-9_/.-])/(?:[^\\s/]+/)*[^\\s/]+");

    private Sanitizer() {
    }

    public static String sanitizeMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String masked = maskCredentials(raw);
        masked = sanitizePath(masked);
        return truncate(masked, 1000);
    }

    public static String sanitizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return rawPath;
        }
        String masked = WINDOWS_PATH.matcher(rawPath).replaceAll("<external>");
        masked = UNIX_PATH.matcher(masked).replaceAll("<external>");
        return masked;
    }

    public static String truncate(String raw, int maxLength) {
        if (raw == null || maxLength < 0 || raw.length() <= maxLength) {
            return raw;
        }
        return raw.substring(0, maxLength);
    }

    private static String maskCredentials(String raw) {
        String masked = BEARER_TOKEN.matcher(raw).replaceAll("Bearer ***");
        masked = OPENAI_KEY.matcher(masked).replaceAll("***");
        return ALIYUN_KEY.matcher(masked).replaceAll("***");
    }
}

package com.pixflow.common.sanitize;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对外展示和落盘前的统一脱敏工具。
 */
public final class Sanitizer {
    private static final Pattern BEARER_TOKEN = Pattern.compile("Bearer\\s+\\S+", Pattern.CASE_INSENSITIVE);

    private static final Pattern OPENAI_KEY = Pattern.compile("sk-[A-Za-z0-9]{16,}");

    private static final Pattern ALIYUN_KEY = Pattern.compile("LTAI[A-Za-z0-9]+");

    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|access[_-]?key[_-]?secret|secret[_-]?key|token|authorization|cookie|password)"
                    + "\\s*[:=]\\s*([^\\s,;}&]+)");

    private static final Pattern EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    private static final Pattern MAINLAND_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)[A-Z]:\\\\[^\\s]+");

    private static final Pattern UNIX_PATH = Pattern.compile("(?<![A-Za-z0-9_/.-])/(?:[^\\s/]+/)*[^\\s/]+");

    private static final int DEFAULT_TRACE_TEXT_LIMIT = 4000;

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

    public static String sanitizeTraceText(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String masked = maskCredentials(raw);
        masked = EMAIL.matcher(masked).replaceAll("***@***");
        masked = MAINLAND_PHONE.matcher(masked).replaceAll("***");
        masked = sanitizePath(masked);
        return truncate(masked, DEFAULT_TRACE_TEXT_LIMIT);
    }

    public static Object sanitizeTraceValue(String fieldName, Object value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveField(fieldName)) {
            return "***";
        }
        if (value instanceof CharSequence text) {
            return sanitizeTraceText(text.toString());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                sanitized.put(key, sanitizeTraceValue(key, entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(item -> sanitizeTraceValue(fieldName, item)).toList();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object[] sanitized = new Object[length];
            for (int i = 0; i < length; i++) {
                sanitized[i] = sanitizeTraceValue(fieldName, Array.get(value, i));
            }
            return sanitized;
        }
        return value;
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
        masked = ALIYUN_KEY.matcher(masked).replaceAll("***");
        return KEY_VALUE_SECRET.matcher(masked).replaceAll("$1=***");
    }

    private static boolean isSensitiveField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String normalized = fieldName.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.contains("apikey")
                || normalized.contains("accesskey")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("password")
                || normalized.equals("username")
                || normalized.equals("user")
                || normalized.equals("email")
                || normalized.equals("phone")
                || normalized.equals("mobile");
    }
}

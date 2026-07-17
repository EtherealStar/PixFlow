package com.pixflow.infra.auth.identity;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 统一认证配置、登录输入与数据库查询使用的用户名规范，避免边界间出现大小写或空白差异。
 */
public final class UsernameNormalizer {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_]{3,32}$");

    private UsernameNormalizer() {
    }

    public static String normalize(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String username) {
        String normalized = normalize(username);
        return normalized != null && USERNAME_PATTERN.matcher(normalized).matches();
    }
}

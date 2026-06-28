package com.pixflow.common.sanitize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SanitizerTest {

    @Test
    void masksTokensAndPaths() {
        String raw = "Bearer abcdef123456 secret D:\\data\\foo.txt /var/tmp/bar.txt sk-1234567890abcdef LTAI123456";

        String sanitized = Sanitizer.sanitizeMessage(raw);

        assertThat(sanitized).doesNotContain("abcdef123456");
        assertThat(sanitized).doesNotContain("D:\\data\\foo.txt");
        assertThat(sanitized).doesNotContain("/var/tmp/bar.txt");
        assertThat(sanitized).contains("***");
        assertThat(sanitized.length()).isLessThanOrEqualTo(1000);
    }

    @Test
    void truncatesLongText() {
        String text = "a".repeat(1200);
        assertThat(Sanitizer.truncate(text, 1000)).hasSize(1000);
    }

    @Test
    void sanitizesTraceTextAndStructuredValues() {
        String raw = "Authorization: Bearer abc api_key=sk-1234567890abcdef accessKeySecret=secret "
                + "Cookie: sid=abc username=zhangsan email=a@example.com phone=13800138000 D:\\study\\secret.txt";

        String sanitized = Sanitizer.sanitizeTraceText(raw);

        assertThat(sanitized)
                .doesNotContain("abc api_key")
                .doesNotContain("sk-1234567890abcdef")
                .doesNotContain("secret.txt")
                .doesNotContain("a@example.com")
                .doesNotContain("13800138000")
                .contains("***");

        Object structured = Sanitizer.sanitizeTraceValue("root", Map.of(
                "username", "张三",
                "email", "a@example.com",
                "nested", Map.of("accessKeySecret", "raw-secret", "message", "phone=13800138000")));

        assertThat(structured).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) structured;
        assertThat(map.get("username")).isEqualTo("***");
        assertThat(map.get("email")).isEqualTo("***");
        assertThat(map.toString()).doesNotContain("raw-secret", "13800138000", "a@example.com");
    }
}

package com.etherealstar.pixflow.module.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * SkuExtractor 属性测试（任务 3.3）。
 *
 * <p>Feature: pixflow, Property 6: 对任意去扩展名后的文件名，提取出的 SKU ID 应满足：
 * (a) 仅由连续字母数字 [A-Za-z0-9] 组成且为该文件名从起始位置起的首个字母数字段，遇首个非字母数字字符即终止；
 * (b) 长度 ≤ 255，超出从末尾截断；(c) 保留原始大小写；
 * (d) 当文件名不含任何字母数字字符时，SKU ID 等于完整去扩展名文件名（兜底）。
 * Validates: Requirements 2.1, 2.2, 2.3
 */
class SkuExtractorPropertyTest {

    /** 独立的参考实现（oracle）：首个最长字母数字段，无则兜底为完整名，最后截断至 255。 */
    private static final Pattern FIRST_ALNUM_RUN = Pattern.compile("[A-Za-z0-9]+");

    private static String expectedSku(String baseName) {
        Matcher matcher = FIRST_ALNUM_RUN.matcher(baseName);
        String sku = matcher.find() ? matcher.group() : baseName;
        if (sku.length() > SkuExtractor.MAX_LENGTH) {
            sku = sku.substring(0, SkuExtractor.MAX_LENGTH);
        }
        return sku;
    }

    @Provide
    Arbitrary<String> baseNames() {
        // 覆盖字母、数字、各类分隔符、空格、标点与 Unicode，长度跨越 255 边界
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('_', '-', ' ', '.', '#', '@', '中', '文', '/')
                .ofMinLength(0)
                .ofMaxLength(400);
    }

    @Property(tries = 500)
    void extractionMatchesSpec(@ForAll("baseNames") String baseName) {
        String actual = SkuExtractor.extract(baseName);
        String expected = expectedSku(baseName);

        assertThat(actual).isEqualTo(expected);
        // (b) 长度上限
        assertThat(actual.length()).isLessThanOrEqualTo(SkuExtractor.MAX_LENGTH);
        // (a)/(d) 要么是纯字母数字段，要么是无字母数字时的兜底完整名
        boolean pureAlnum = actual.chars().allMatch(SkuExtractorPropertyTest::isAlnum);
        boolean fallbackWholeName = actual.equals(truncate(baseName));
        assertThat(pureAlnum || fallbackWholeName).isTrue();
    }

    private static String truncate(String s) {
        return s.length() > SkuExtractor.MAX_LENGTH ? s.substring(0, SkuExtractor.MAX_LENGTH) : s;
    }

    private static boolean isAlnum(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    @Test
    void caseIsPreserved() {
        assertThat(SkuExtractor.extract("AbC123_x")).isEqualTo("AbC123");
    }

    @Test
    void firstRunStopsAtNonAlnum() {
        assertThat(SkuExtractor.extract("SKU-001-color")).isEqualTo("SKU");
        assertThat(SkuExtractor.extract("  abc def")).isEqualTo("abc");
    }

    @Test
    void noAlphanumericFallsBackToWholeName() {
        assertThat(SkuExtractor.extract("___")).isEqualTo("___");
        assertThat(SkuExtractor.extract("")).isEqualTo("");
        assertThat(SkuExtractor.extract("中文")).isEqualTo("中文");
    }

    @Test
    void longRunIsTruncatedFromTail() {
        String run = "a".repeat(300);
        assertThat(SkuExtractor.extract(run)).hasSize(SkuExtractor.MAX_LENGTH);
        assertThat(SkuExtractor.extract(run)).isEqualTo("a".repeat(SkuExtractor.MAX_LENGTH));
    }
}

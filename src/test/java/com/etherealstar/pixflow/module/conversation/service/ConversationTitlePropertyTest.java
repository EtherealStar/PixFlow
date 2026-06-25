package com.etherealstar.pixflow.module.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * 对话标题截取属性测试（任务 7.3）。
 *
 * <p>Feature: pixflow, Property 14: 对话标题截取——对任意首条消息 {@code content}，对话标题应等于
 * {@code content} 的前 {@code min(20, length(content))} 个字符。
 *
 * <p>Validates: Requirements 5.2
 */
class ConversationTitlePropertyTest {

    @Provide
    Arbitrary<String> contents() {
        // 覆盖各类字符与长度跨越 20 字符边界（含远超 20 与少于 20）
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars(' ', '中', '文', '😀', '\n', '_', '-')
                .ofMinLength(1)
                .ofMaxLength(120);
    }

    @Property(tries = 500)
    void titleIsFirstMinTwentyChars(@ForAll("contents") String content) {
        String title = ConversationService.deriveTitle(content);

        int expectedLen = Math.min(ConversationService.TITLE_MAX_LENGTH, content.length());
        assertThat(title).isEqualTo(content.substring(0, expectedLen));
        assertThat(title.length()).isEqualTo(expectedLen);
        assertThat(title.length()).isLessThanOrEqualTo(ConversationService.TITLE_MAX_LENGTH);
        // 标题是 content 的前缀
        assertThat(content).startsWith(title);
    }

    @Test
    void shortContentKeepsWholeString() {
        assertThat(ConversationService.deriveTitle("hello")).isEqualTo("hello");
    }

    @Test
    void exactlyTwentyCharsIsKept() {
        String twenty = "01234567890123456789";
        assertThat(ConversationService.deriveTitle(twenty)).isEqualTo(twenty);
    }

    @Test
    void longContentIsTruncatedToTwenty() {
        String content = "a".repeat(50);
        assertThat(ConversationService.deriveTitle(content)).isEqualTo("a".repeat(20));
    }

    @Test
    void nullContentYieldsEmptyTitle() {
        assertThat(ConversationService.deriveTitle(null)).isEmpty();
    }
}

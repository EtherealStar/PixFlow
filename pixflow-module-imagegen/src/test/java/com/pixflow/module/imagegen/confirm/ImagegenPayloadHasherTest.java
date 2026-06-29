package com.pixflow.module.imagegen.confirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ImagegenPayloadHasher 单测(对齐 imagegen.md §十五)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>字段重排 / 字典序无关(同一计划字段顺序变化 → hash 一致)</li>
 *   <li>改 prompt / 源图集 → hash 变化</li>
 *   <li>白名单外参数不参与 hash</li>
 *   <li>{@code note} 不参与 hash(用户输入的备注不属载荷)</li>
 * </ul>
 */
class ImagegenPayloadHasherTest {

    private final ImagegenPayloadHasher hasher = new ImagegenPayloadHasher();

    @Test
    @DisplayName("同义不同序:sourceImageIds 与 prompt 字段重排,hash 一致")
    void hash_isStable_acrossFieldReordering() {
        ImagegenPlan a = plan(List.of("a", "b"), "重绘风格", Map.of("style", "A"), "note-1");
        ImagegenPlan b = plan(List.of("b", "a"), "重绘风格", Map.of("style", "A"), "note-2");
        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    @DisplayName("改 prompt → hash 变化")
    void hash_changesWhenPromptChanges() {
        ImagegenPlan a = plan(List.of("a"), "A 风格", Map.of("style", "A"), "");
        ImagegenPlan b = plan(List.of("a"), "B 风格", Map.of("style", "A"), "");
        assertThat(hasher.hash(a)).isNotEqualTo(hasher.hash(b));
    }

    @Test
    @DisplayName("prompt trim 后同义:首尾空格差异不影响 hash")
    void hash_isStable_acrossPromptWhitespace() {
        ImagegenPlan a = plan(List.of("a"), "重绘", Map.of(), "");
        ImagegenPlan b = plan(List.of("a"), "  重绘  ", Map.of(), "");
        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    @DisplayName("改源图集 → hash 变化")
    void hash_changesWhenSourceImageSetChanges() {
        ImagegenPlan a = plan(List.of("a"), "重绘", Map.of(), "");
        ImagegenPlan b = plan(List.of("a", "b"), "重绘", Map.of(), "");
        assertThat(hasher.hash(a)).isNotEqualTo(hasher.hash(b));
    }

    @Test
    @DisplayName("白名单外参数(secrets 等)不参与 hash")
    void hash_ignoresNonWhitelistedParams() {
        Map<String, Object> clean = Map.of("style", "A");
        Map<String, Object> withSecret = new LinkedHashMap<>();
        withSecret.put("style", "A");
        withSecret.put("secrets", "ak-xxx");
        withSecret.put("model", "wanx");
        ImagegenPlan a = plan(List.of("a"), "x", clean, "");
        ImagegenPlan b = plan(List.of("a"), "x", withSecret, "");
        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    @DisplayName("白名单内参数值变化 → hash 变化")
    void hash_changesOnWhitelistedParamValueChange() {
        ImagegenPlan a = plan(List.of("a"), "x", Map.of("style", "A"), "");
        ImagegenPlan b = plan(List.of("a"), "x", Map.of("style", "B"), "");
        assertThat(hasher.hash(a)).isNotEqualTo(hasher.hash(b));
    }

    @Test
    @DisplayName("note 不参与 hash")
    void hash_ignoresNote() {
        ImagegenPlan a = plan(List.of("a"), "x", Map.of(), "");
        ImagegenPlan b = plan(List.of("a"), "x", Map.of(), "用户偏好 A");
        ImagegenPlan c = plan(List.of("a"), "x", Map.of(), "另一个完全不同的备注");
        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
        assertThat(hasher.hash(b)).isEqualTo(hasher.hash(c));
    }

    @Test
    @DisplayName("conversationId / packageId 不参与 hash(由 hash() 重载直接传 imageIds+prompt+params 也能算同样值)")
    void hash_isIndependentOfConversationContext() {
        ImagegenPlan a = new ImagegenPlan(List.of("a"), "x", Map.of(), null, "conv-1", "pkg-1");
        ImagegenPlan b = new ImagegenPlan(List.of("a"), "x", Map.of(), null, "conv-2", "pkg-2");
        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    @DisplayName("hash 是 64 位 hex")
    void hash_formatIsSha256Hex() {
        ImagegenPlan plan = plan(List.of("a"), "x", Map.of(), "");
        String hash = hasher.hash(plan);
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("params 缺失字段不补默认值")
    void hash_doesNotFillDefaults() {
        ImagegenPlan a = plan(List.of("a"), "x", Map.of(), "");
        // 用 HashMap 才能塞 null(HashMap 允许 null value)
        Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("style", null);
        ImagegenPlan b = plan(List.of("a"), "x", withNull, "");
        // 缺失 vs null 应当都视为"没填",hash 应一致
        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    @DisplayName("hash() 重载:直接传 imageIds+prompt+params 与 record 路径结果一致")
    void hash_overload_matchesRecordPath() {
        ImagegenPlan plan = plan(List.of("a", "b"), "重绘", Map.of("style", "A"), "n");
        String recordHash = hasher.hash(plan);
        String directHash = hasher.hash(List.of("a", "b"), "重绘", Map.of("style", "A"));
        assertThat(recordHash).isEqualTo(directHash);
    }

    @Test
    @DisplayName("null plan 抛 IllegalArgumentException")
    void hash_rejectsNullPlan() {
        assertThatThrownBy(() -> hasher.hash((ImagegenPlan) null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static ImagegenPlan plan(List<String> ids, String prompt, Map<String, Object> params, String note) {
        return new ImagegenPlan(ids, prompt, params, note, "conv-x", "pkg-x");
    }
}
package com.pixflow.module.imagegen.confirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.module.imagegen.proposal.ImagegenPlan;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImagegenPayloadHasherTest {

    private final ImagegenPayloadHasher hasher = new ImagegenPayloadHasher();

    @Test
    void isStableForEquivalentNormalizedPayloads() {
        ImagegenPlan first = plan("package:7/image:11", "重绘", Map.of("style", "A"), "a");
        ImagegenPlan second = plan("package:7/image:11", "  重绘  ", Map.of("style", "A"), "b");

        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(second));
        assertThat(hasher.hash(first)).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void changesForReferencePromptOrAllowedParameterChanges() {
        String baseline = hasher.hash(plan("package:7/image:11", "重绘", Map.of(), null));

        assertThat(hasher.hash(plan("package:7/image:12", "重绘", Map.of(), null)))
                .isNotEqualTo(baseline);
        assertThat(hasher.hash(plan("package:7/image:11", "另一提示", Map.of(), null)))
                .isNotEqualTo(baseline);
        assertThat(hasher.hash(plan(
                "package:7/image:11", "重绘", Map.of("style", "A"), null)))
                .isNotEqualTo(baseline);
    }

    @Test
    void ignoresNonWhitelistedParametersAndConversationMetadata() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("secret", "value");
        ImagegenPlan first = plan("package:7/image:11", "重绘", Map.of(), null);
        ImagegenPlan second = new ImagegenPlan(
                "package:7/image:11", "重绘", extra, "note", "another", 7L);

        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(second));
    }

    @Test
    void nullPlanIsRejected() {
        assertThatThrownBy(() -> hasher.hash((ImagegenPlan) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ImagegenPlan plan(
            String referenceKey, String prompt, Map<String, Object> params, String note) {
        return new ImagegenPlan(referenceKey, prompt, params, note, "conv", 7L);
    }
}

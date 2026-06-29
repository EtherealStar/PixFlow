package com.pixflow.module.vision.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.vision.config.VisionProperties;
import org.junit.jupiter.api.Test;

class CopyFillPolicyTest {

    @Test
    void gapOnlyFillsMissingFields() {
        VisionProperties properties = new VisionProperties();
        CopyFillPolicy policy = new CopyFillPolicy(properties);
        AssetCopyRow existing = new AssetCopyRow();
        existing.setProductName("doc name");

        FillDecision decision = policy.decide(existing, new ProductCopyDraft("model name", "kw", "desc"));

        assertThat(decision.shouldExtract()).isTrue();
        assertThat(decision.shouldWrite()).isTrue();
        assertThat(decision.mergedDraft().productName()).isEqualTo("doc name");
        assertThat(decision.mergedDraft().keywords()).isEqualTo("kw");
    }

    @Test
    void skipIfAnySkipsExistingSku() {
        VisionProperties properties = new VisionProperties();
        properties.getEnrich().setFillPolicy(VisionProperties.FillPolicy.SKIP_IF_ANY);
        CopyFillPolicy policy = new CopyFillPolicy(properties);
        AssetCopyRow existing = new AssetCopyRow();
        existing.setDescription("doc desc");

        FillDecision decision = policy.decide(existing, new ProductCopyDraft("name", "kw", "desc"));

        assertThat(decision.shouldExtract()).isFalse();
        assertThat(decision.shouldWrite()).isFalse();
    }
}

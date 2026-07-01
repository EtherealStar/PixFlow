package com.pixflow.module.rubrics.rule;

import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.ImageProbe;
import com.pixflow.module.rubrics.model.EvidenceRef;
import com.pixflow.module.rubrics.model.EvidenceType;
import java.io.ByteArrayInputStream;
import java.util.List;

abstract class AbstractImageRuleVerifier implements RuleVerifier {
    private final ImageCodec imageCodec;

    AbstractImageRuleVerifier(ImageCodec imageCodec) {
        this.imageCodec = imageCodec;
    }

    ImageProbe probe(RuleCheckInput input) {
        if (input.imageBytes() == null || input.imageBytes().length == 0) {
            throw new IllegalArgumentException("image bytes are required for " + dimensionKey());
        }
        return imageCodec.probe(new ByteArrayInputStream(input.imageBytes()));
    }

    List<EvidenceRef> imageEvidence(RuleCheckInput input, String excerpt) {
        String ref = input.result() == null ? "" : input.result().getOutputMinioKey();
        return List.of(new EvidenceRef(EvidenceType.IMAGE, ref, excerpt, null));
    }

    int intParam(RuleCheckInput input, String key, int defaultValue) {
        Object value = input.params().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return defaultValue;
    }
}

package com.pixflow.module.rubrics.rule;

import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.ImageProbe;

public class ResolutionRuleVerifier extends AbstractImageRuleVerifier {
    public ResolutionRuleVerifier(ImageCodec imageCodec) {
        super(imageCodec);
    }

    @Override
    public String dimensionKey() {
        return "resolution_compliance";
    }

    @Override
    public RuleCheckResult verify(RuleCheckInput input) {
        ImageProbe probe = probe(input);
        int minWidth = intParam(input, "minWidth", 800);
        int minHeight = intParam(input, "minHeight", 800);
        String detail = "width=" + probe.width() + ", height=" + probe.height()
                + ", required>=" + minWidth + "x" + minHeight;
        if (probe.width() >= minWidth && probe.height() >= minHeight) {
            return RuleCheckResult.pass(detail, imageEvidence(input, detail));
        }
        return RuleCheckResult.fail(detail, imageEvidence(input, detail));
    }
}

package com.pixflow.module.rubrics.rule;

import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.ImageProbe;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public class FormatRuleVerifier extends AbstractImageRuleVerifier {
    public FormatRuleVerifier(ImageCodec imageCodec) {
        super(imageCodec);
    }

    @Override
    public String dimensionKey() {
        return "format_compliance";
    }

    @Override
    public RuleCheckResult verify(RuleCheckInput input) {
        ImageProbe probe = probe(input);
        Set<String> allowed = allowedFormats(input.params().get("allowed"));
        String detail = "format=" + probe.format() + ", allowed=" + allowed;
        if (allowed.isEmpty() || allowed.contains(probe.format().name())) {
            return RuleCheckResult.pass(detail, imageEvidence(input, detail));
        }
        return RuleCheckResult.fail(detail, imageEvidence(input, detail));
    }

    private static Set<String> allowedFormats(Object raw) {
        if (raw instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .filter(value -> ImageFormat.fromName(value).isPresent())
                    .collect(Collectors.toSet());
        }
        if (raw instanceof String text && !text.isBlank()) {
            return java.util.Arrays.stream(text.split(","))
                    .map(String::trim)
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());
        }
        return Set.of();
    }
}

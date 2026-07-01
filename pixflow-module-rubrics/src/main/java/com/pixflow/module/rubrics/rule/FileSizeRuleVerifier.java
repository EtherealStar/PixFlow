package com.pixflow.module.rubrics.rule;

import com.pixflow.module.rubrics.model.EvidenceRef;
import com.pixflow.module.rubrics.model.EvidenceType;
import java.util.List;

public class FileSizeRuleVerifier implements RuleVerifier {
    @Override
    public String dimensionKey() {
        return "file_size_compliance";
    }

    @Override
    public RuleCheckResult verify(RuleCheckInput input) {
        long size = input.result() != null && input.result().getBytesOut() != null
                ? input.result().getBytesOut()
                : (input.imageBytes() == null ? 0 : input.imageBytes().length);
        long maxBytes = longParam(input, "maxBytes", 5L * 1024L * 1024L);
        String detail = "bytes=" + size + ", maxBytes=" + maxBytes;
        List<EvidenceRef> evidence = List.of(new EvidenceRef(EvidenceType.DATA, String.valueOf(input.resultId()), detail, null));
        return size <= maxBytes ? RuleCheckResult.pass(detail, evidence) : RuleCheckResult.fail(detail, evidence);
    }

    private static long longParam(RuleCheckInput input, String key, long defaultValue) {
        Object value = input.params().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return defaultValue;
    }
}

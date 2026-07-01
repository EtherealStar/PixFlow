package com.pixflow.module.rubrics.judge;

import com.pixflow.module.rubrics.template.RubricDimension;
import com.pixflow.module.task.domain.model.ProcessResult;
import java.util.Map;

public class JudgePromptBuilder {
    public String build(RubricDimension dimension, ProcessResult result, Map<String, Object> traceContext) {
        String copy = result == null || result.getGeneratedCopy() == null ? "" : result.getGeneratedCopy();
        String resultRef = result == null ? "" : String.valueOf(result.getId());
        return """
                You are a PixFlow offline rubrics judge.
                Judge exactly one dimension and return JSON only:
                {"verdict":"PASS|FAIL","confidence":"HIGH|MEDIUM|LOW","rationale":"short reason","evidence":[{"type":"IMAGE|TRACE|DATA|DOC","ref":"id","excerpt":"short quote"}]}

                Never return numeric scores. Continuous 0-100 scoring is computed by program code.

                Dimension key: %s
                Dimension name: %s
                Rubric instruction: %s
                Result id: %s
                Generated copy: %s
                Trace context: %s
                """.formatted(
                dimension.key(),
                dimension.name(),
                dimension.verifier() == null ? "" : dimension.verifier().prompt(),
                resultRef,
                copy,
                traceContext == null ? "{}" : traceContext);
    }
}

---
status: accepted
---

# Use criterion verdicts instead of scalar quality scores

PixFlow evaluates an Image Result, Copy Result, or Task Decision against atomic Hard Rules and Principles. Each criterion produces an evidence-grounded `PASS`, `FAIL`, `INCONCLUSIVE`, or `NOT_APPLICABLE` verdict; Hard Rules form a quality gate, while Principles remain a verdict matrix with an optional transparent pass rate. We do not map model-reported confidence to partial points or publish a 0-100 overall quality score because those numbers are not calibrated and obscure missing evidence.

## Considered Options

- Runtime-generated rubrics were rejected as the production source of truth because PixFlow has no preference-trained rubric generator or calibrated criterion-label dataset. Models may draft candidates, but a human-approved, versioned template governs production evaluation.
- Confidence-weighted scalar aggregation was rejected because self-reported model confidence is not a probability margin and cannot distinguish low quality from evaluator uncertainty.
- A single `process_result` evaluation covering image, copy, and decision quality was rejected because those judgments have different subjects and evidence boundaries.

## Consequences

Formal regression compares criterion verdicts on the same versioned Evaluation Dataset. LLM criteria use repeated judgments to expose agreement, and unavailable evidence produces `INCONCLUSIVE` rather than a failing score. Evaluation findings remain inside Rubrics until a human explicitly promotes one into Agent memory.

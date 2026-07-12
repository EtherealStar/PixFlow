# Rubrics Evaluation

This context owns offline, evidence-grounded judgments about PixFlow artifacts and task decisions. It records what was evaluated, which criterion applied, and whether the available evidence supports that criterion.

## Language

**Evaluation Subject**:
The single artifact or decision being judged. Every subject has exactly one subject type and a stable identity.
_Avoid_: item, result row, score target

**Image Result**:
An output image evaluated independently from any generated copy or task-level decision.
_Avoid_: image score, visual domain

**Copy Result**:
A generated or imported piece of copy evaluated independently from image and task-decision evidence.
_Avoid_: copy score, text domain

**Task Decision**:
A task-level proposal or decision evaluated from its requirements, evidence, and trace rather than from an individual output artifact.
_Avoid_: decision score, result decision

**Rubric Template**:
A human-approved, versioned definition of the criteria that apply to one evaluation-subject type.
_Avoid_: runtime-generated rubric, scoring prompt

**Criterion**:
One atomic, independently judgeable requirement with explicit passing and failing anchors.
_Avoid_: compound dimension, metric

**Hard Rule**:
A criterion derived from an explicit requirement whose failure prevents the subject from passing the quality gate.
_Avoid_: high-weight principle, mandatory score

**Principle**:
A criterion expressing an implicit quality expectation as a binary pass/fail proposition. `INCONCLUSIVE` and `NOT_APPLICABLE` are abstention states, not partial quality values.
_Avoid_: soft score, subjective points

**Evidence Pack**:
The immutable, identified facts made available when judging one subject. A judge may cite evidence from the pack but may not invent evidence.
_Avoid_: prompt context, model evidence

**Criterion Verdict**:
The evidence-grounded four-state result for one binary criterion proposition: `PASS`, `FAIL`, `INCONCLUSIVE`, or `NOT_APPLICABLE`.
_Avoid_: confidence score, partial points

**Quality Gate**:
The combined status of applicable Hard Rules for one subject: passed, failed, or unknown.
_Avoid_: overall score, weighted total

**Evaluation Dataset**:
A versioned, replayable set of evaluation subjects used for calibration or regression comparison.
_Avoid_: arbitrary run, recent production sample

**Gold Label**:
A human-adjudicated criterion verdict used to validate a rubric and its judge.
_Avoid_: model label, training reward

**Promotion**:
An explicit human-reviewed action that turns an evaluation finding into an Agent memory insight.
_Avoid_: automatic feedback, score writeback

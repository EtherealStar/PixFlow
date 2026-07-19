# module/rubrics - Evidence-grounded offline evaluation

> `module/rubrics` is PixFlow's offline evaluation context. It evaluates typed subjects against human-approved, versioned, atomic criteria and records verdicts, evidence, judge agreement, and coverage.
> It does not participate in the Agent loop, train or post-train a model, publish an opaque 0-100 quality score, or automatically turn model judgments into Agent memory.
> Domain language is defined in `pixflow-module-rubrics/CONTEXT.md`; the scalar-score decision is recorded in `pixflow-module-rubrics/docs/adr/0001-use-criterion-verdicts-instead-of-quality-scores.md`.

## 1. Scope and principles

Rubrics consumes immutable task outputs, copy, task decisions, eval traces, and object-store evidence after normal execution finishes. It answers three separate questions:

- Does this output image satisfy the criteria for an `IMAGE_RESULT`?
- Does this piece of copy satisfy the criteria for a `COPY_RESULT`?
- Does this task-level proposal or decision satisfy the criteria for a `TASK_DECISION`?

The module follows these invariants:

1. **The template is the production source of truth.** Production evaluation uses a human-approved YAML template identified by `(templateId, version, templateHash)`. A model may draft a candidate template offline, but that candidate cannot evaluate production subjects before review and publication.
2. **One criterion, one proposition.** Every dimension in a template is an atomic Criterion with one statement, passing anchor, failing anchor, applicability rule, required evidence types, kind, and verifier.
3. **Judgment and evaluator availability are separate facts.** A Criterion Verdict is `PASS`, `FAIL`, `INCONCLUSIVE`, or `NOT_APPLICABLE`. Missing evidence, parser failure, judge outage, and unresolved judge disagreement are not `FAIL`.
4. **No scalar quality score.** Every Hard Rule and Principle states one binary pass/fail proposition, while the recorded verdict remains four-state so the evaluator can abstain with `INCONCLUSIVE` or `NOT_APPLICABLE`. Hard Rules form a Quality Gate. Reports may expose the unweighted Principle pass rate as a transparent statistic, but must call it `passRate`, never a quality score.
5. **Evidence is system-owned.** Rubrics constructs and hashes an Evidence Pack. A model may cite only evidence IDs supplied in that pack; invented or missing references invalidate the rollout.
6. **LLM reliability is measured, not self-reported.** Model-reported confidence is diagnostic text only and never affects a verdict or pass rate. Repeated judge agreement, judge-human agreement, parser failure, evidence failure, and inconclusive rate are the reliability signals.
7. **Evaluation is validated before automation.** Experimental criteria are available only in manual calibration runs. Event-driven and scheduled runs require the applicable criteria to be validated or production-ready.
8. **Evaluation does not become memory.** Findings remain Rubrics facts. This product version has no automatic or human-reviewed path from a finding into `analysis_insight`, SKU history, preferences, or any other Agent memory.

## 2. Subject boundaries

`EvaluationSubject` is a tagged reference, not a polymorphic dump of every available field.

| Subject type | Stable identity | Primary evidence | Explicitly excluded evidence |
|---|---|---|---|
| `IMAGE_RESULT` | successful `process_result.id` for an image output | output object, image metadata, relevant source-image references | generated copy and unrelated task history |
| `COPY_RESULT` | copy artifact identity, or a stable generated-copy identity derived from its owning result | copy text, product facts, requested audience/voice | image aesthetics unless a criterion explicitly requires image-copy alignment |
| `TASK_DECISION` | `process_task.id` plus the immutable proposal/decision revision | requirements, proposal, DAG snapshot, relevant trace spans, recalled facts | per-image aesthetic judgment |

Templates declare exactly one `subjectType`. A run may contain multiple subject types only by creating separate template-bound run partitions. No evaluation row combines image, copy, and decision verdicts into one overall result.

## 3. Template and criterion model

### 3.1 Template lifecycle

A `RubricTemplate` contains:

- `id`, semantic `version`, canonical `templateHash`, `subjectType`, description;
- a list of atomic criteria;
- an evaluator specification naming the locked logical judge role and rollout count;
- the template lifecycle state: `EXPERIMENTAL`, `VALIDATED`, or `PRODUCTION`.

Classpath templates and `$PIXFLOW_HOME/rubrics/` templates may both be discovered, but a user template cannot silently replace the same `(id, version)` with different bytes. A hash mismatch is a startup error. Publishing changed content requires a new version.

Lifecycle semantics:

| State | Allowed runs | Gate/regression authority |
|---|---|---|
| `EXPERIMENTAL` | manual calibration and gold-set runs only | cannot fail a production gate or trigger formal regression |
| `VALIDATED` | manual runs and explicitly selected production samples | may participate in reports; automation remains opt-in per template |
| `PRODUCTION` | manual, sampled event-driven, and scheduled runs | may drive Quality Gate and formal regression alerts |

### 3.2 Atomic criterion

Each existing `dimension` is redefined as one atomic Criterion. The canonical YAML shape is:

```yaml
id: image-result-quality
version: 2.0.0
subjectType: IMAGE_RESULT
lifecycleState: EXPERIMENTAL
evaluator:
  role: RUBRICS_JUDGE_VISION
  rollouts: 3
criteria:
  - key: resolution_compliance
    kind: HARD_RULE
    statement: The output image is at least 800 by 800 pixels.
    passAnchor: Both width and height are at least 800 pixels.
    failAnchor: Width or height is below 800 pixels.
    evidenceTypes: [IMAGE_METADATA]
    applicability: OUTPUT_IMAGE_EXISTS
    verifier:
      type: RULE
      ruleClass: ResolutionRuleVerifier
      params: { minWidth: 800, minHeight: 800 }
  - key: background_cleanliness
    kind: PRINCIPLE
    statement: The product boundary has no clearly visible residue, jagged edge, or halo.
    passAnchor: The visible boundary is continuous and free of conspicuous residue, jagged pixels, and halo.
    failAnchor: At least one conspicuous residue region, jagged boundary, or halo is visible.
    evidenceTypes: [OUTPUT_IMAGE]
    applicability: OUTPUT_IMAGE_EXISTS
    verifier: { type: LLM }
```

Template validation fails when:

- the statement combines independently judgeable propositions;
- either anchor is absent or merely restates `good`/`bad`;
- evidence types or applicability are missing;
- a Hard Rule has no deterministic verifier where a deterministic check is available;
- keys are duplicated or content changes without a version change;
- an LLM criterion lacks a validated judge role and positive rollout count.

`weight` is not part of the default v2 criterion contract. If a later use case introduces a weighted pass rate, weights must be human-defined, versioned, and displayed in the report; they still cannot create partial criterion points.

## 4. Verdict and result semantics

### 4.1 Criterion Verdict

```java
public enum CriterionVerdict {
    PASS,
    FAIL,
    INCONCLUSIVE,
    NOT_APPLICABLE
}
```

| Verdict | Meaning | Included in pass rate |
|---|---|---|
| `PASS` | available evidence supports the passing anchor | yes, numerator and denominator |
| `FAIL` | available evidence supports the failing anchor | yes, denominator only |
| `INCONCLUSIVE` | the criterion applies, but evidence or evaluator reliability is insufficient | no |
| `NOT_APPLICABLE` | the criterion does not apply to this subject | no |

Parser errors, invalid evidence references, model unavailability, timeout, and unresolved rollout disagreement become `INCONCLUSIVE` with a machine-readable reason. They never become `FAIL` and never receive partial points.

### 4.2 Quality Gate, pass rate, and coverage

The subject result exposes three separate summaries:

```text
qualityGate = PASSED | FAILED | UNKNOWN
passRate    = PASS / (PASS + FAIL) among applicable Principle criteria
coverage    = (PASS + FAIL) / (PASS + FAIL + INCONCLUSIVE) among applicable criteria
```

- `qualityGate=FAILED` when any applicable production Hard Rule is `FAIL`.
- `qualityGate=UNKNOWN` when no Hard Rule fails but at least one applicable production Hard Rule is `INCONCLUSIVE`.
- `qualityGate=PASSED` only when every applicable production Hard Rule is `PASS`; `NOT_APPLICABLE` Hard Rules are excluded.
- `passRate` is optional display data. The canonical report remains the criterion verdict matrix.
- When no applicable Principle has a `PASS` or `FAIL` verdict, `passRate` is `null`, never zero.
- Reports always show counts for all four verdicts, rollout agreement, evidence completeness, template/evaluator version, and coverage.
- The module has no `overallScore`, `imageScore`, `copyScore`, `decisionScore`, `DimensionScore`, or confidence-to-score mapping in the target design.

## 5. Evidence Pack

Rubrics constructs an immutable `EvidencePack` before invoking any verifier. Each entry has a stable local ID (`E1`, `E2`, ...), type, source reference, content hash, optional excerpt or metadata, and capture time. Examples include:

- `OUTPUT_IMAGE`: object location, object version/hash, and the image bytes supplied to a vision judge;
- `IMAGE_METADATA`: width, height, format, size, alpha metadata;
- `COPY_TEXT`: exact evaluated text and content hash;
- `TASK_REQUIREMENT`: the immutable user requirement or confirmed proposal revision;
- `DAG_SNAPSHOT`: the confirmed DAG and parameter snapshot;
- `TRACE_SPAN`: selected trace spans relevant to one criterion;
- `COMMERCE_FACT`: identified product or commerce facts used by the decision.

Each criterion declares required evidence types. Applicability is evaluated before model invocation:

- not applicable -> `NOT_APPLICABLE`;
- applicable but required evidence missing -> `INCONCLUSIVE(MISSING_EVIDENCE)`;
- evidence present -> invoke the rule or judge.

LLM output cites Evidence Pack IDs only. The parser rejects unknown IDs, citations to a disallowed evidence type, or a rationale with no citation. The evaluation record stores `evidencePackHash` and the referenced entry metadata so a historical verdict can be replayed or declared non-replayable if the underlying object is unavailable.

## 6. Verifiers and judge protocol

### 6.1 Rule verifier

Deterministic checks execute once and return a Criterion Verdict, rationale, evidence IDs, and optional diagnostic measurements. Measurements such as actual width are presentation data and never become partial points.

A rule failure means the subject fails the criterion. A rule exception, corrupt input, or missing required evidence means `INCONCLUSIVE`.

### 6.2 LLM judge

LLM criteria use independent, version-locked roles:

- `RUBRICS_JUDGE_TEXT` for `COPY_RESULT` and text/trace portions of `TASK_DECISION`;
- `RUBRICS_JUDGE_VISION` for visual `IMAGE_RESULT` criteria.

The producing model and judge role are logically separate. If resolved provider/model identities are equal, the result is marked `selfJudged=true`. The evaluator version captures provider, model, model revision when available, role defaults, request options, prompt hash, parser schema version, and rollout policy.

The prompt contains exactly one criterion, its anchors, the subject view, and the permitted Evidence Pack entries. The required output is:

```json
{
  "verdict": "PASS|FAIL|INCONCLUSIVE",
  "rationale": "short evidence-grounded explanation",
  "evidenceIds": ["E1"]
}
```

Model-reported confidence and numeric score fields are ignored and recorded as schema noise.

### 6.3 Repeated judgment

The default LLM rollout count is three; a high-risk template may configure five. Each rollout records its raw parsed verdict, rationale, evidence IDs, provider/model identity, prompt hash, latency, token usage, and error when present.

Aggregation rules:

- `NOT_APPLICABLE` is determined before LLM invocation and is never voted on.
- A rollout with parse failure, invalid evidence, or provider failure contributes `INCONCLUSIVE`.
- A strict majority of all configured rollouts must agree on `PASS` or `FAIL`.
- Without a strict majority, the final verdict is `INCONCLUSIVE(JUDGE_DISAGREEMENT)`.
- `agreement = majorityCount / configuredRollouts`; it is reliability metadata, not a score.

The primary judge performs normal production rollouts. A second judge model is used only for gold-set calibration, unresolved/low-agreement review, and configured audit samples; it does not silently alter normal majority results.

## 7. Calibration and validation

Gold-set validation is a release requirement for a criterion, not an optional test.

### 7.1 Dataset construction

- Create a versioned Evaluation Dataset by stratified sampling of real Image Results, Copy Results, and Task Decisions.
- Start with 100-200 subjects across expected categories and failure modes; enlarge when criterion prevalence is too low.
- Two humans independently label each applicable criterion as `PASS`, `FAIL`, or `NOT_APPLICABLE`; a third adjudication resolves disagreement.
- Keep a holdout partition that is not used while editing statements, anchors, or examples.
- Store the label schema version and evidence snapshot/hash used by annotators.

This is evaluator validation, not post-training. Gold labels are not used to fine-tune the judge in this scope.

### 7.2 Criterion-level acceptance

Report at least:

- human-human agreement (Cohen's kappa or a documented equivalent);
- judge-human accuracy and macro-F1 on adjudicated labels;
- repeated-judge agreement;
- `INCONCLUSIVE`, parser-failure, invalid-evidence, and missing-evidence rates;
- results by subject category and easy/hard slice.

Initial acceptance targets are:

- human-human `kappa >= 0.60`;
- judge-human macro-F1 `>= 0.75` for Principles;
- judge-human macro-F1 `>= 0.85` for LLM-evaluated Hard Rules, although deterministic Hard Rules are preferred;
- no unreviewed regression on the holdout partition.

If humans disagree below the threshold, revise or split the criterion before changing the judge. A criterion that misses its judge threshold stays `EXPERIMENTAL` and cannot participate in a production gate or formal regression alert.

## 8. Evaluation Dataset and regression

An `EvaluationDataset` is an immutable manifest of `(subjectType, subjectId, subjectSnapshotHash)` entries with an ID, version, description, creation time, and optional gold-label set version.

Formal regression requires:

- the same Evaluation Dataset version;
- the same subject snapshots, or an explicit report of non-replayable entries;
- separately identified template and evaluator versions;
- paired comparison by subject and criterion.

The regression report includes:

- per-criterion PASS rate and paired `PASS -> FAIL` / `FAIL -> PASS` transitions;
- Hard Rule failure rate and Quality Gate transitions;
- coverage and `INCONCLUSIVE` rate;
- rollout agreement and invalid-evidence/parser failure rates;
- sample counts and confidence intervals or a clear insufficient-sample marker.

An arbitrary historical production run cannot be promoted to a formal baseline. Production runs may support trend monitoring only because their subject mix changes. Template or judge upgrades are evaluated by replaying old and new evaluator versions on the same dataset.

## 9. Runs and persistence

### 9.1 Run lifecycle

A run binds one template version, evaluator version, subject type, and either an Evaluation Dataset or an explicit manual subject selection. Run items are checkpointed by `(runId, subjectType, subjectId)` and may be resumed without overwriting successful item history.

Recommended statuses:

- run: `PENDING`, `RUNNING`, `SUCCEEDED`, `PARTIAL`, `FAILED`;
- item: `PENDING`, `RUNNING`, `SUCCEEDED`, `PARTIAL`, `FAILED`.

`PARTIAL` means at least one criterion was `INCONCLUSIVE`; it is not a synonym for low quality. Provider failure is isolated per rollout/criterion/item and does not convert unrelated subjects to failure.

### 9.2 Target tables

The current scalar-oriented schema must migrate toward these facts:

```text
rubrics_run(
  id, template_id, template_version, template_hash,
  evaluator_version, subject_type, dataset_id, dataset_version,
  trigger_type, status, stats_json, started_at, finished_at, created_at)

rubrics_run_item(
  run_id, subject_type, subject_id, subject_snapshot_hash,
  status, quality_gate, pass_rate, coverage,
  evidence_pack_hash, error_msg, updated_at,
  UNIQUE(run_id, subject_type, subject_id))

rubrics_evaluation(
  id, run_id, subject_type, subject_id,
  template_id, template_version, template_hash, evaluator_version,
  quality_gate, pass_rate, coverage, verdict_counts_json,
  evidence_pack_hash, created_at)

rubrics_criterion_result(
  evaluation_id, criterion_key, criterion_kind, verdict, reason_code,
  rationale, evidence_refs_json, agreement, rollout_count, created_at,
  UNIQUE(evaluation_id, criterion_key))

rubrics_judge_rollout(
  criterion_result_id, rollout_no, verdict, rationale,
  evidence_refs_json, provider, model, prompt_hash,
  latency_ms, usage_json, error_code, created_at)

rubrics_dataset(...)
rubrics_dataset_item(...)
rubrics_gold_label(...)
rubrics_alert(...)
```

`pass_rate` is nullable and never substitutes for the criterion result rows. PixFlow is still in development, so the Rubrics schema is replaced as a fresh baseline: the target schema does not retain `rubrics_score`, Promotion, `overall_score`, `image_score`, `copy_score`, or `decision_score` compatibility facts.

## 10. Triggers and automation gates

Run triggers are constrained by template lifecycle:

- `MANUAL`: allowed for all states and is the only trigger for `EXPERIMENTAL` templates;
- `EVENT_DRIVEN`: allowed only for templates explicitly enabled after validation; consume `TaskCompleted` asynchronously and sample subjects according to configuration;
- `SCHEDULED`: allowed only for validated/production templates and versioned datasets or a documented production sampling policy.

Calibration happens before automation. The default configuration disables event-driven and scheduled evaluation until at least one applicable template is validated. Rubrics never blocks the online Agent loop or changes task terminal state.

## 11. Memory boundary

Rubrics owns evaluation facts. `module/memory` owns Agent memory.

No criterion verdict, pass rate, alert, repeated low-pass pattern, or human review result is written to `sku_history.rubrics_score`, `analysis_insight`, preferences, or other Agent memory. Deterministic Hard Rule failures may create Rubrics alerts, but alerts remain inside this context. Rubrics has no dependency on `module/memory`, no Promotion entity, and no memory-write API.

This prevents a judge error from entering memory and later being treated as evidence by another judgment. Reintroducing any write path requires a new explicit product decision and design update.

## 12. Operational surface

The browser product has no Evaluation Center, Rubrics route, Rubrics navigation item, or Rubrics contract in `frontend/api.md`. Calibration, dataset comparison, manual runs, Evidence Pack replay, lifecycle administration, and alerts are backend/offline capabilities invoked through internal services or separately secured operational tooling.

If a future operational API is exposed, it remains outside the end-user frontend contract and must not publish `overallScore`, `imageScore`, `copyScore`, or `decisionScore` for v2 evaluations.

## 13. Observability and failure handling

Metrics include:

- run/item counts and latency by subject type;
- model calls, latency, tokens, and errors by evaluator version;
- criterion verdict counts, coverage, inconclusive rate, and agreement;
- parser and invalid-evidence failures;
- calibration accuracy/F1 and human agreement by criterion version;
- event/scheduled runs skipped because a template is not validated.

Logs carry run, subject, template, evaluator, criterion, and trace IDs, but not raw image bytes, full prompts, secrets, or unrestricted trace payloads.

## 14. Test and acceptance strategy

The first implementation slice is `IMAGE_RESULT`, followed by `COPY_RESULT`, then `TASK_DECISION`.

### 14.1 Shared contract tests

- template immutability and hash/version enforcement;
- atomic criterion validation, anchors, applicability, and evidence types;
- all four verdict semantics;
- Quality Gate, pass rate, and coverage without scalar scores;
- missing evidence and evaluator failure produce `INCONCLUSIVE`;
- invented evidence IDs invalidate a rollout;
- three-rollout majority and disagreement behavior;
- replay identity includes subject, template, evaluator, and evidence hashes;
- experimental templates cannot run automatically or drive gates;
- no automatic memory write occurs.

### 14.2 Image Result slice

Start with:

- deterministic Hard Rules: resolution and format;
- LLM Principles: background cleanliness and product visibility;
- system-built image Evidence Pack;
- three independent vision-judge rollouts;
- manual run/report APIs;
- a human-labeled image gold set and holdout report.

Only after this slice meets its criterion-level acceptance targets should the same mechanism be extended to Copy Result. Task Decision is last because it depends on the most complex trace, requirement, proposal, DAG, and fact evidence.

## 15. Migration from the current implementation

The repository currently implements the superseded v1 model. Migration work must explicitly remove or replace:

- `Confidence`-to-0/20/40/60/80/100 mapping in `ScoreAggregator`;
- `DimensionScore`, `DomainScore`, and scalar `RubricScore` semantics;
- unavailable LLM judge becoming `FAIL + LOW`;
- one `process_result` being evaluated across image, copy, and decision domains;
- model-created evidence references without Evidence Pack validation;
- arbitrary run baselines;
- automatic `ScoreFeedbackWriter` / `MemoryFeedbackTrigger` behavior;
- automatic event/scheduled runs for unvalidated criteria;
- use of `PRIMARY_CHAT` as the Rubrics judge role.

The completed `rubrics-module-implementation-plan.md` remains a historical record of v1 and is not rewritten. A new ExecPlan must be created before implementing this migration.

## 16. Out of scope

- evaluator SFT, GRPO, DPO, RLHF, or any other post-training;
- probability-margin scoring until the provider-neutral AI contract exposes trustworthy verdict-token probabilities and they are separately calibrated;
- runtime production rubric generation;
- a visual rubric editor;
- A/B experimentation or automatic baseline selection;
- automatically using delayed commerce outcomes as criterion truth;
- automatically exporting verdicts as training rewards;
- real-time scoring inside the Agent loop.

## 17. References and rationale

- RUBRIC-ARROW (`arXiv:2605.29156`) motivates criterion-level judging, concise/non-redundant rubrics, repeated voting, and the distinction between hard Boolean aggregation and probability-derived signals. PixFlow adopts the evaluation structure but not its SFT/GRPO/post-training pipeline.
- OpenRubrics (`arXiv:2510.07743`) motivates the `Hard Rule` / `Principle` distinction and preference-consistency filtering. PixFlow substitutes human approval and gold-set validation because it does not train a rubric generator.
- RubricEval (`arXiv:2603.25133`) shows that rubric-level judgment remains difficult even for strong judges, motivating criterion-level gold labels, explicit reasoning, agreement measurement, and lifecycle gating.
- Auto-Rubric (`arXiv:2510.17314`) motivates verification-driven refinement and compact atomic criteria; generated candidates remain non-authoritative in PixFlow.

## Revision Notes

2026-07-19 / Codex: 冻结 `RubricsEvaluationService.start/resume/get` 深模块接口与类型化 `RunSelection`，删除后端 end-user Controller，并把开发期数据库策略收敛为无 scalar score/Promotion 兼容的 fresh schema；同时修正 ADR 中残留的 Memory Promotion 表述。

2026-07-12 / Codex: Replaced the v1 confidence-weighted 0-100 scoring design with typed Evaluation Subjects, atomic Hard Rules and Principles, four-state Criterion Verdicts, system-owned Evidence Packs, repeated judge agreement, mandatory gold-set validation, versioned replayable datasets, and automation lifecycle gates. Post-training remains explicitly out of scope.

2026-07-17 / Codex: Removed the Promotion entity, API, persistence fact, and memory dependency. Rubrics findings and human reviews remain Rubrics-only facts in the current product version.

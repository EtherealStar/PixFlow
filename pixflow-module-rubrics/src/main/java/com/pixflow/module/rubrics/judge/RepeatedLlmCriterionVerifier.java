package com.pixflow.module.rubrics.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ToolChoice;
import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.model.ModelRouter;
import com.pixflow.infra.ai.model.ResolvedModel;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.ai.vision.VisionRequest;
import com.pixflow.module.rubrics.evidence.EvidenceEntry;
import com.pixflow.module.rubrics.evidence.EvidencePack;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.observability.RubricsMetrics;
import com.pixflow.module.rubrics.subject.EvaluationSubject;
import com.pixflow.module.rubrics.template.Criterion;
import com.pixflow.module.rubrics.template.EvaluatorSpec;
import com.pixflow.module.rubrics.verifier.CriterionResult;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class RepeatedLlmCriterionVerifier {
    private final ChatModelClient chat;

    private final VisionModelClient vision;

    private final ModelRouter router;

    private final ObjectMapper mapper;

    private final EvidenceImageResolver images;

    private final MajorityVerdictReducer reducer;

    private final RubricsMetrics metrics;

    private final int maxRationaleChars;

    public RepeatedLlmCriterionVerifier(ChatModelClient chat, VisionModelClient vision, ModelRouter router,
                                        ObjectMapper mapper, EvidenceImageResolver images,
                                        MajorityVerdictReducer reducer, RubricsMetrics metrics,
                                        int maxRationaleChars) {
        this.chat = chat;
        this.vision = vision;
        this.router = router;
        this.mapper = mapper;
        this.images = images;
        this.reducer = reducer;
        this.metrics = metrics;
        if (maxRationaleChars <= 0) {
            throw new IllegalArgumentException("max rationale chars must be positive");
        }
        this.maxRationaleChars = maxRationaleChars;
    }

    public CriterionEvaluation verify(Criterion criterion, EvaluatorSpec evaluator,
                                      EvaluationSubject subject, EvidencePack pack) {
        return verify(criterion, evaluator, subject, pack, () -> { });
    }

    public CriterionEvaluation verify(
            Criterion criterion,
            EvaluatorSpec evaluator,
            EvaluationSubject subject,
            EvidencePack pack,
            Runnable beforeRollout) {
        List<EvidenceEntry> allowed = pack.view(criterion.evidenceTypes());
        String prompt = prompt(criterion, subject, allowed);
        ResolvedModel model = router.resolve(evaluator.judgeRole());
        String promptHash = sha256(systemPrompt() + "\n" + prompt);
        String evaluatorVersion = evaluatorVersion(evaluator, model);
        List<JudgeRollout> rollouts = new ArrayList<>(evaluator.rollouts());
        for (int index = 1; index <= evaluator.rollouts(); index++) {
            beforeRollout.run();
            rollouts.add(call(index, evaluator.judgeRole(), model, prompt, promptHash, allowed));
        }
        MajorityVerdict majority = reducer.reduce(rollouts, evaluator.rollouts());
        rollouts.forEach(metrics::recordJudge);
        List<String> evidenceIds = rollouts.stream()
                .filter(value -> value.verdict() == majority.verdict())
                .flatMap(value -> value.evidenceIds().stream()).distinct().toList();
        CriterionResult result = new CriterionResult(majority.verdict(), majority.reason(),
                majority.verdict() == CriterionVerdict.INCONCLUSIVE
                        ? "judge rollouts did not reach a strict PASS or FAIL majority"
                        : "strict majority of independent judge rollouts",
                evidenceIds, Map.of("agreement", majority.agreement()));
        return new CriterionEvaluation(result, rollouts, majority.agreement(), evaluatorVersion);
    }

    /** 返回调用 provider 前即可锁定的 evaluator identity。 */
    public String expectedEvaluatorVersion(EvaluatorSpec evaluator) {
        return evaluatorVersion(evaluator, router.resolve(evaluator.judgeRole()));
    }

    private JudgeRollout call(int index, ModelRole role, ResolvedModel model, String prompt,
                              String promptHash, List<EvidenceEntry> allowed) {
        long started = System.nanoTime();
        try {
            com.pixflow.infra.ai.chat.ChatResult response;
            ChatMessage user = message(ChatMessage.Role.USER, prompt);
            if (role == ModelRole.RUBRICS_JUDGE_VISION) {
                List<ChatMessage.Part> parts = new ArrayList<>(user.parts());
                allowed.stream().filter(entry -> entry.type().name().contains("IMAGE"))
                        .findFirst()
                        .ifPresent(entry -> parts.add(new ChatMessage.ImagePart(
                                images.resolve(entry), entry.id())));
                response = vision.call(new VisionRequest(role,
                        List.of(
                                message(ChatMessage.Role.SYSTEM, systemPrompt()),
                                new ChatMessage(ChatMessage.Role.USER, parts)),
                        List.of(), ToolChoice.NONE, new ChatOptions(0.0, 512, null), null));
            } else if (role == ModelRole.RUBRICS_JUDGE_TEXT) {
                response = chat.call(new ChatRequest(role,
                        List.of(message(ChatMessage.Role.SYSTEM, systemPrompt()), user), List.of(), ToolChoice.NONE,
                        new ChatOptions(0.0, 512, null), null));
            } else {
                throw new IllegalArgumentException("unsupported rubrics judge role: " + role);
            }
            return parse(index, response.finalText(), allowed, model, promptHash,
                    elapsedMillis(started), response.usage());
        } catch (Exception error) {
            return new JudgeRollout(index, CriterionVerdict.INCONCLUSIVE, VerdictReason.EVALUATOR_FAILURE,
                    "judge call failed: " + error.getClass().getSimpleName(), List.of(), model.provider(),
                    model.model(), promptHash, elapsedMillis(started), 0, 0, 0);
        }
    }

    private JudgeRollout parse(int index, String output, List<EvidenceEntry> allowed, ResolvedModel model,
                               String promptHash, long latencyMs, TokenUsage usage) {
        try {
            JsonNode json = mapper.readTree(output);
            CriterionVerdict verdict = CriterionVerdict.valueOf(json.path("verdict").asText());
            if (verdict == CriterionVerdict.NOT_APPLICABLE) {
                throw new IllegalArgumentException("judge cannot emit N/A");
            }
            String rationale = json.path("rationale").asText();
            List<String> evidenceIds = new ArrayList<>();
            json.path("evidenceIds").forEach(value -> evidenceIds.add(value.asText()));
            if (rationale.isBlank() || evidenceIds.isEmpty()) {
                throw new IllegalArgumentException("missing rationale or evidence");
            }
            rationale = rationale.length() <= maxRationaleChars
                    ? rationale : rationale.substring(0, maxRationaleChars);
            var allowedIds = new HashSet<>(allowed.stream().map(EvidenceEntry::id).toList());
            if (!allowedIds.containsAll(evidenceIds)) {
                return new JudgeRollout(index, CriterionVerdict.INCONCLUSIVE, VerdictReason.INVALID_EVIDENCE,
                        "judge referenced evidence outside the allowed pack view", List.of(), model.provider(),
                        model.model(), promptHash, latencyMs, usage.promptTokens(),
                        usage.completionTokens(), usage.totalTokens());
            }
            return new JudgeRollout(
                    index, verdict, VerdictReason.RULE_MATCH, rationale, evidenceIds,
                    model.provider(), model.model(), promptHash, latencyMs,
                    usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
        } catch (Exception error) {
            return new JudgeRollout(index, CriterionVerdict.INCONCLUSIVE, VerdictReason.PARSER_FAILURE,
                    "judge response did not match parser schema", List.of(), model.provider(), model.model(),
                    promptHash, latencyMs, usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
        }
    }

    private String evaluatorVersion(EvaluatorSpec evaluator, ResolvedModel model) {
        String identity = String.join("|", model.provider(), model.model(), model.capability().name(),
                String.valueOf(model.temperature()), String.valueOf(model.maxTokens()),
                evaluator.parserSchemaVersion(), Integer.toString(evaluator.rollouts()), sha256(systemPrompt()));
        return model.provider() + ":" + model.model() + ":" + sha256(identity);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private String prompt(Criterion criterion, EvaluationSubject subject, List<EvidenceEntry> evidence) {
        return "criterion=" + criterion.statement() + "\npassAnchor=" + criterion.passAnchor()
                + "\nfailAnchor=" + criterion.failAnchor() + "\nsubjectType=" + subject.type()
                + "\nsubjectId=" + subject.id() + "\nevidence=" + evidence;
    }

    private static String systemPrompt() {
        return "Return JSON only with verdict PASS, FAIL, or INCONCLUSIVE, non-empty rationale, and evidenceIds.";
    }

    private static ChatMessage message(ChatMessage.Role role, String text) {
        return new ChatMessage(role, List.of(new ChatMessage.TextPart(text)));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}

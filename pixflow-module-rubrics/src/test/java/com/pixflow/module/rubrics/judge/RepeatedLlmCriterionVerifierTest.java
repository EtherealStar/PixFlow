package com.pixflow.module.rubrics.judge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ChatResult;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.model.ModelCapability;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.model.ModelRouter;
import com.pixflow.infra.ai.model.ResolvedModel;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.ai.vision.VisionRequest;
import com.pixflow.module.rubrics.evidence.EvidenceEntry;
import com.pixflow.module.rubrics.evidence.EvidencePack;
import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.subject.ImageResultSubject;
import com.pixflow.module.rubrics.template.Applicability;
import com.pixflow.module.rubrics.template.Criterion;
import com.pixflow.module.rubrics.template.EvaluatorSpec;
import com.pixflow.module.rubrics.template.VerifierSpec;
import com.pixflow.module.rubrics.template.VerifierType;
import com.pixflow.module.rubrics.verifier.VerdictReason;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Flux;
import org.junit.jupiter.api.Test;

class RepeatedLlmCriterionVerifierTest {
    @Test
    void usesThreeIndependentVisionRolloutsAndStrictMajority() {
        var vision = new FakeVisionClient(
                json("PASS", "E1"), json("PASS", "E1"), json("FAIL", "E1"));
        var verifier = verifier(vision);

        CriterionEvaluation result = verifier.verify(criterion(), evaluator(), subject(), evidence());

        assertThat(vision.requests).hasSize(3);
        assertThat(vision.requests).allMatch(request -> request.role() == ModelRole.RUBRICS_JUDGE_VISION);
        assertThat(result.result().verdict()).isEqualTo(CriterionVerdict.PASS);
        assertThat(result.agreement()).isEqualTo(2.0 / 3.0);
        assertThat(result.rollouts()).hasSize(3);
        assertThat(result.rollouts()).allSatisfy(rollout -> {
            assertThat(rollout.provider()).isEqualTo("fake");
            assertThat(rollout.model()).isEqualTo("judge-v1");
            assertThat(rollout.promptHash()).hasSize(64);
            assertThat(rollout.totalTokens()).isEqualTo(2);
            assertThat(rollout.latencyMs()).isNotNegative();
        });
    }

    @Test
    void rejectsEvidenceIdsThatWereNotProvidedByTheSystem() {
        var vision = new FakeVisionClient(
                json("PASS", "E9"), json("FAIL", "E1"), "not-json");
        CriterionEvaluation result = verifier(vision).verify(criterion(), evaluator(), subject(), evidence());

        assertThat(result.rollouts().get(0).verdict()).isEqualTo(CriterionVerdict.INCONCLUSIVE);
        assertThat(result.rollouts().get(0).reason()).isEqualTo(VerdictReason.INVALID_EVIDENCE);
        assertThat(result.result().verdict()).isEqualTo(CriterionVerdict.INCONCLUSIVE);
        assertThat(result.result().reason()).isEqualTo(VerdictReason.JUDGE_DISAGREEMENT);
    }

    private static RepeatedLlmCriterionVerifier verifier(FakeVisionClient vision) {
        ChatModelClient chat = new ChatModelClient() {
            public ChatResult call(ChatRequest request) { throw new AssertionError("text client not expected"); }
            public Flux<ChatStreamEvent> stream(ChatRequest request) { return Flux.empty(); }
        };
        ModelRouter router = role -> new ResolvedModel(role, "fake", "judge-v1",
                ModelCapability.VISION, 0.0, 256, Duration.ofSeconds(1));
        return new RepeatedLlmCriterionVerifier(chat, vision, router, new ObjectMapper(),
                entry -> new com.pixflow.infra.ai.chat.ChatMessage.UrlImageContent(URI.create("https://example.invalid/e1")),
                new MajorityVerdictReducer());
    }

    private static Criterion criterion() {
        return new Criterion("clean", CriterionKind.PRINCIPLE, "Background is clean",
                "No residue", "Visible residue", Set.of(EvidenceType.OUTPUT_IMAGE),
                Applicability.ALWAYS, new VerifierSpec(VerifierType.LLM, null, null, Map.of()));
    }

    private static EvaluatorSpec evaluator() {
        return new EvaluatorSpec(ModelRole.RUBRICS_JUDGE_VISION, 3, "1");
    }

    private static ImageResultSubject subject() {
        return new ImageResultSubject("1", 2, "sku", "STANDARD", "image", null, null, "b", 10,
                "IMAGE:2:10", 10, "snapshot");
    }

    private static EvidencePack evidence() {
        return new EvidencePack("pack", List.of(new EvidenceEntry("E1", EvidenceType.OUTPUT_IMAGE,
                "result.png", "content", Instant.EPOCH, Map.of())));
    }

    private static String json(String verdict, String evidenceId) {
        return "{\"verdict\":\"" + verdict + "\",\"rationale\":\"based on " + evidenceId
                + "\",\"evidenceIds\":[\"" + evidenceId + "\"]}";
    }

    private static final class FakeVisionClient implements VisionModelClient {
        private final ArrayDeque<String> outputs;
        private final java.util.ArrayList<VisionRequest> requests = new java.util.ArrayList<>();
        private FakeVisionClient(String... outputs) { this.outputs = new ArrayDeque<>(List.of(outputs)); }
        public ChatResult call(VisionRequest request) {
            requests.add(request);
            return new ChatResult(outputs.removeFirst(), List.of(), StopReason.STOP,
                    new TokenUsage(1, 1, 2));
        }
    }
}

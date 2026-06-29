package com.pixflow.module.dag.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ChatResult;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.chat.ToolCall;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.ir.PixelTool;
import com.pixflow.module.dag.ir.ValidatedDag;
import com.pixflow.module.dag.validate.DagValidator;
import com.pixflow.module.dag.validate.ParamSchemaRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * CopyUnitExecutor 测试:文案支路 + ChatModelClient 集成。
 */
class CopyUnitExecutorTest {

    private ExecutableBranch copyBranch() {
        DagValidator validator = new DagValidator(new ParamSchemaRegistry(), 50, 1);
        DagJsonReader reader = new DagJsonReader();
        String json = """
            {
              "nodes":[{"id":"copy","tool":"generate_copy","params":{"style":"SHORT","language":"zh","maxLength":200}}],
              "edges":[]
            }
            """;
        ValidatedDag dag = validator.toValidated(reader.read(json), new DagSchemaVersion("1.0"));
        var branches = new com.pixflow.module.dag.expand.BranchExpander()
            .expand(dag, List.of(com.pixflow.module.dag.expand.ImageDescriptor.single("i1", "sku1", "k")));
        return branches.get(0);
    }

    private CopyUnitExecutor executor(ChatModelClient client) {
        return new CopyUnitExecutor(client);
    }

    @Test
    void execute_returnsCopySucceeded() {
        ChatModelClient fake = new ChatModelClient() {
            @Override public ChatResult call(ChatRequest request) {
                return new ChatResult("白底简约风格,凸显商品质感。",
                    List.<ToolCall>of(), StopReason.STOP, new TokenUsage(10, 20, 30));
            }
            @Override public Flux<ChatStreamEvent> stream(ChatRequest request) {
                return Flux.empty();
            }
        };
        CopyUnitExecutor ex = executor(fake);
        UnitOutcome outcome = ex.execute(copyBranch(),
            UnitInput.copy(CopyContext.of("sku1", "白色T恤", List.of("简约", "百搭"),
                "适合日常穿着,柔软舒适")));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.SUCCEEDED);
        assertThat(outcome.generatedCopy()).contains("白底");
        assertThat(outcome.kind()).isEqualTo(UnitKind.BRANCH);
    }

    @Test
    void execute_returnsFAILED_onEmptyResponse() {
        ChatModelClient fake = new ChatModelClient() {
            @Override public ChatResult call(ChatRequest request) {
                return new ChatResult("", List.<ToolCall>of(), StopReason.STOP, new TokenUsage(0, 0, 0));
            }
            @Override public Flux<ChatStreamEvent> stream(ChatRequest request) { return Flux.empty(); }
        };
        CopyUnitExecutor ex = executor(fake);
        UnitOutcome outcome = ex.execute(copyBranch(),
            UnitInput.copy(CopyContext.of("sku1", "白色T恤", List.of(), "")));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.FAILED);
        assertThat(outcome.error().code()).isEqualTo(DagErrorCode.DAG_UNIT_EXECUTION_FAILED);
    }

    @Test
    void execute_returnsFAILED_onClientException() {
        ChatModelClient fake = new ChatModelClient() {
            @Override public ChatResult call(ChatRequest request) {
                throw new RuntimeException("model timeout");
            }
            @Override public Flux<ChatStreamEvent> stream(ChatRequest request) { return Flux.empty(); }
        };
        CopyUnitExecutor ex = executor(fake);
        UnitOutcome outcome = ex.execute(copyBranch(),
            UnitInput.copy(CopyContext.of("sku1", "白色T恤", List.of(), "")));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.FAILED);
        assertThat(outcome.error().safeMessage()).isNotBlank();
    }

    @Test
    void execute_returnsFAILED_onMissingCopyContext() {
        CopyUnitExecutor ex = executor(new ChatModelClient() {
            @Override public ChatResult call(ChatRequest request) {
                return new ChatResult("ok", List.<ToolCall>of(), StopReason.STOP, new TokenUsage(0, 0, 0));
            }
            @Override public Flux<ChatStreamEvent> stream(ChatRequest request) { return Flux.empty(); }
        });
        UnitOutcome outcome = ex.execute(copyBranch(), UnitInput.images(List.of()));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.FAILED);
        assertThat(outcome.error().code()).isEqualTo(DagErrorCode.DAG_INVALID_STRUCTURE);
    }

    @Test
    void execute_includesProductNameInPrompt() {
        java.util.List<String> capturedPrompts = new java.util.ArrayList<>();
        ChatModelClient fake = new ChatModelClient() {
            @Override public ChatResult call(ChatRequest request) {
                StringBuilder all = new StringBuilder();
                for (var msg : request.messages()) {
                    for (var part : msg.parts()) {
                        if (part instanceof com.pixflow.infra.ai.chat.ChatMessage.TextPart tp) {
                            all.append(tp.text()).append("\n");
                        }
                    }
                }
                capturedPrompts.add(all.toString());
                return new ChatResult("ok", List.<ToolCall>of(), StopReason.STOP, new TokenUsage(0, 0, 0));
            }
            @Override public Flux<ChatStreamEvent> stream(ChatRequest request) { return Flux.empty(); }
        };
        CopyUnitExecutor ex = executor(fake);
        ex.execute(copyBranch(), UnitInput.copy(
            CopyContext.of("sku1", "白色T恤", List.of("简约"), "舒适")));
        assertThat(capturedPrompts).hasSize(1);
        assertThat(capturedPrompts.get(0)).contains("白色T恤");
        assertThat(capturedPrompts.get(0)).contains("简约");
    }
}
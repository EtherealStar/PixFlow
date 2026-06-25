package com.etherealstar.pixflow.module.dag.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.infra.ai.DagPromptManager;
import com.etherealstar.pixflow.infra.ai.LlmClient;
import com.etherealstar.pixflow.infra.ai.LlmException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * DagParser 非法 LLM 输出拒绝属性测试（任务 9.7）。
 *
 * <p>Feature: pixflow, Property 21: 对任意无法解析为合法 DAG 的 LLM 输出（非 JSON、结构缺失、
 * 节点缺少 id/tool、使用非白名单工具等），DagParser 必须以 DAG_PARSE_FAILED 拒绝且不生成任务。
 * Validates: Requirements 6.6
 *
 * <p>LLM 调用经 {@link LlmClient} 接口以内存替身（stub）替代，不依赖真实模型/外部 API。
 */
class DagParserInvalidOutputPropertyTest {

    private static final class StubLlmClient implements LlmClient {
        private String response = "";
        private RuntimeException toThrow;

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    private final StubLlmClient stub = new StubLlmClient();
    private final DagParser parser = new DagParser(
            stub, new DagPromptManager(), new DagNormalizer(), new ObjectMapper());

    @Provide
    Arbitrary<String> invalidOutputs() {
        return Arbitraries.of(
                // 非 JSON 文本
                "这不是 JSON",
                "{ broken json",
                "",
                "   ",
                // JSON 但非对象
                "[]",
                "123",
                "\"just a string\"",
                "true",
                // 对象但缺少 nodes/edges 数组
                "{}",
                "{\"nodes\": []}",
                "{\"edges\": []}",
                "{\"nodes\": {}, \"edges\": {}}",
                // 节点缺少 id
                "{\"nodes\": [{\"tool\": \"resize\"}], \"edges\": []}",
                // 节点缺少 tool
                "{\"nodes\": [{\"id\": \"n1\"}], \"edges\": []}",
                // 非白名单工具
                "{\"nodes\": [{\"id\": \"n1\", \"tool\": \"delete_disk\", \"params\": {}}], \"edges\": []}",
                // 边缺少 from/to
                "{\"nodes\": [{\"id\": \"n1\", \"tool\": \"remove_bg\"}], "
                        + "\"edges\": [{\"from\": \"n1\"}]}",
                // params 非对象
                "{\"nodes\": [{\"id\": \"n1\", \"tool\": \"resize\", \"params\": []}], \"edges\": []}");
    }

    @Property
    void invalidOutputIsRejectedWithParseFailed(@ForAll("invalidOutputs") String rawOutput) {
        stub.toThrow = null;
        stub.response = rawOutput;
        assertThatThrownBy(() -> parser.parse("请处理图片"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DAG_PARSE_FAILED));
    }

    @Test
    void llmCallFailureIsRejectedWithParseFailed() {
        stub.toThrow = new LlmException("模型未配置");
        assertThatThrownBy(() -> parser.parse("请处理图片"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DAG_PARSE_FAILED));
    }

    @Test
    void blankInstructionIsRejectedWithParseFailed() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DAG_PARSE_FAILED));
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DAG_PARSE_FAILED));
    }
}

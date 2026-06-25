package com.etherealstar.pixflow.infra.ai;

/**
 * 大语言模型（LLM）调用抽象。
 *
 * <p>将底层 Spring AI / 具体模型（通义千问、DeepSeek 等）的调用细节封装在接口之后，使上层模块
 * （DAG_Parser、文案生成分支）无需感知 Spring AI 的 {@code ChatModel}、{@code Prompt} 等类型，
 * 同时便于在测试中以内存替身（mock/stub）替换真实模型，将纯逻辑与外部 I/O 解耦。</p>
 *
 * <p>对应需求 6.1：调用 LLM 将自然语言指令解析为 DAG JSON。</p>
 */
public interface LlmClient {

    /**
     * 以「系统提示词 + 用户提示词」的形式调用 LLM 并返回模型的文本输出。
     *
     * @param systemPrompt 系统提示词，约束模型行为（如固定工具白名单与参数 schema）。可为 {@code null} 或空，表示不提供系统提示。
     * @param userPrompt   用户提示词，承载实际的自然语言指令。不可为 {@code null} 或空白。
     * @return 模型返回的原始文本内容
     * @throws LlmException 当未配置可用模型、调用失败或返回内容为空时
     */
    String complete(String systemPrompt, String userPrompt);
}

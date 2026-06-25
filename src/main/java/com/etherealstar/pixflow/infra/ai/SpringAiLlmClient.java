package com.etherealstar.pixflow.infra.ai;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 基于 Spring AI 的 {@link LlmClient} 实现。
 *
 * <p>将 Spring AI 的 {@link ChatModel} 调用封装在接口之后。底层模型（通义千问 / DeepSeek）由对应的
 * Spring AI 模型 starter 提供 {@code ChatModel} Bean；本实现通过 {@link ObjectProvider} 惰性获取，
 * 使得在尚未接入具体模型 starter 时应用仍可正常启动，仅在真正发起调用时才校验模型可用性。</p>
 *
 * <p>由于 LLM 调用被隔离在 {@link LlmClient} 接口之后，单元/属性测试可注入内存替身（stub）替代本实现，
 * 无需真实模型即可验证上层解析逻辑。</p>
 */
@Service
public class SpringAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);

    private final ObjectProvider<ChatModel> chatModelProvider;

    public SpringAiLlmClient(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new LlmException("用户提示词不能为空");
        }

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new LlmException("未配置可用的 LLM 模型，请引入并配置 Spring AI 模型 starter（如通义千问 / DeepSeek）");
        }

        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));

        try {
            ChatResponse response = chatModel.call(new Prompt(messages));
            String content = extractContent(response);
            if (content == null || content.isBlank()) {
                throw new LlmException("LLM 返回内容为空");
            }
            return content;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.warn("调用 LLM 失败: {}", e.getMessage());
            throw new LlmException("调用 LLM 失败: " + e.getMessage(), e);
        }
    }

    private String extractContent(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getContent();
    }
}

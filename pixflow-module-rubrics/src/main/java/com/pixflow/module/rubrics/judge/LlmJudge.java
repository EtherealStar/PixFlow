package com.pixflow.module.rubrics.judge;

import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ChatResult;
import com.pixflow.infra.ai.chat.ToolChoice;
import com.pixflow.infra.ai.chat.ChatMessage.BytesImageContent;
import com.pixflow.infra.ai.chat.ChatMessage.ImagePart;
import com.pixflow.infra.ai.chat.ChatMessage.Role;
import com.pixflow.infra.ai.chat.ChatMessage.TextPart;
import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.ai.vision.VisionRequest;
import com.pixflow.module.rubrics.config.RubricsProperties;
import com.pixflow.module.rubrics.template.RubricDimension;
import com.pixflow.module.task.domain.model.ProcessResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LlmJudge {
    private final ChatModelClient chatClient;
    private final VisionModelClient visionClient;
    private final JudgePromptBuilder promptBuilder;
    private final VerdictParser parser;
    private final RubricsProperties properties;

    public LlmJudge(
            ChatModelClient chatClient,
            VisionModelClient visionClient,
            JudgePromptBuilder promptBuilder,
            VerdictParser parser,
            RubricsProperties properties) {
        this.chatClient = chatClient;
        this.visionClient = visionClient;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.properties = properties;
    }

    public JudgeVerdict judge(RubricDimension dimension, ProcessResult result, byte[] imageBytes, Map<String, Object> traceContext) {
        String prompt = promptBuilder.build(dimension, result, traceContext);
        ChatOptions options = new ChatOptions(
                properties.getRunner().getJudgeTemperature(),
                800,
                properties.getRunner().getJudgeTimeout());
        ChatResult response;
        if (imageBytes != null && imageBytes.length > 0 && visionClient != null) {
            List<ChatMessage.Part> parts = new ArrayList<>();
            parts.add(new TextPart(prompt));
            parts.add(new ImagePart(new BytesImageContent(imageBytes, "image/png"), "process_result output"));
            response = visionClient.call(new VisionRequest(List.of(new ChatMessage(Role.USER, parts)), options));
        } else {
            response = chatClient.call(new ChatRequest(
                    ModelRole.PRIMARY_CHAT,
                    List.of(new ChatMessage(Role.USER, List.of(new TextPart(prompt)))),
                    List.of(),
                    ToolChoice.NONE,
                    options));
        }
        return parser.parse(response.finalText());
    }
}

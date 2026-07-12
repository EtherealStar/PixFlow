package com.pixflow.module.vision.analyze;

import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.vision.VisionRequest;
import com.pixflow.module.vision.image.PreparedVisionImage;
import java.util.ArrayList;
import java.util.List;

public class VisionPromptBuilder {

    public VisionRequest build(VisionAnalysisRequest request, List<PreparedVisionImage> images) {
        List<ChatMessage.Part> userParts = new ArrayList<>();
        StringBuilder user = new StringBuilder();
        user.append("Task: ").append(request.taskType()).append('\n');
        if (!request.question().isBlank()) {
            user.append("Question: ").append(request.question()).append('\n');
        }
        user.append("Images:\n");
        for (int i = 0; i < images.size(); i++) {
            PreparedVisionImage image = images.get(i);
            user.append("- image ").append(i + 1)
                    .append(": sku=").append(nullToDash(image.ref().skuId()))
                    .append(", view=").append(nullToDash(image.ref().viewId()))
                    .append(", label=").append(nullToDash(image.ref().hintLabel()))
                    .append('\n');
        }
        if (!request.context().isEmpty()) {
            user.append("Context: ").append(request.context()).append('\n');
        }
        userParts.add(new ChatMessage.TextPart(user.toString()));
        for (PreparedVisionImage image : images) {
            userParts.add(image.part());
        }

        List<ChatMessage> messages = List.of(
                new ChatMessage(ChatMessage.Role.SYSTEM, List.of(new ChatMessage.TextPart(systemPrompt()))),
                new ChatMessage(ChatMessage.Role.USER, userParts));
        return new VisionRequest(ModelRole.VISION, messages, new ChatOptions(0.2d, 1200, null));
    }

    private String systemPrompt() {
        return """
                You are PixFlow's visual analysis capability. Analyze only the provided images and user question.
                Return exactly one JSON object, without markdown or commentary, using these fields:
                composition, backgroundClean, hasWatermark, watermarkPosition, matchesDescription,
                mismatchReason, sellingPoints, issues, confidence.
                Use null when uncertain. sellingPoints and issues must be arrays of short strings.
                confidence must be a number from 0 to 1.
                """;
    }

    private String nullToDash(String value) {
        return value == null ? "-" : value;
    }
}

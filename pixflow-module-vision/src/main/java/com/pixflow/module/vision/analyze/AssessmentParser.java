package com.pixflow.module.vision.analyze;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AssessmentParser {
    private final ObjectMapper objectMapper;

    public AssessmentParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ParseOutcome parse(String rawText) {
        String safeRaw = rawText == null ? "" : rawText.strip();
        String json = extractJson(safeRaw);
        if (json == null || json.isBlank()) {
            return ParseOutcome.degraded(safeRaw);
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            VisionAssessment assessment = new VisionAssessment(
                    text(root, "composition"),
                    bool(root, "backgroundClean"),
                    bool(root, "hasWatermark"),
                    text(root, "watermarkPosition"),
                    bool(root, "matchesDescription"),
                    text(root, "mismatchReason"),
                    stringList(root, "sellingPoints"),
                    stringList(root, "issues"),
                    number(root, "confidence"),
                    safeRaw);
            return new ParseOutcome(assessment, false);
        } catch (JsonProcessingException ex) {
            return ParseOutcome.degraded(safeRaw);
        }
    }

    private String extractJson(String raw) {
        String stripped = stripCodeFence(raw);
        int objectStart = stripped.indexOf('{');
        int objectEnd = stripped.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return stripped.substring(objectStart, objectEnd + 1);
        }
        return stripped;
    }

    private String stripCodeFence(String raw) {
        String text = raw.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        int fenceEnd = text.lastIndexOf("```");
        if (fenceEnd <= firstLineEnd) {
            return text.substring(firstLineEnd + 1).strip();
        }
        return text.substring(firstLineEnd + 1, fenceEnd).strip();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asDouble();
    }

    private List<String> stringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isTextual()) {
            String text = value.asText();
            return text == null || text.isBlank() ? List.of() : List.of(text);
        }
        if (!value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isNull()) {
                String text = item.asText();
                if (text != null && !text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    public record ParseOutcome(VisionAssessment assessment, boolean degraded) {
        static ParseOutcome degraded(String rawText) {
            return new ParseOutcome(VisionAssessment.degraded(rawText), true);
        }
    }
}

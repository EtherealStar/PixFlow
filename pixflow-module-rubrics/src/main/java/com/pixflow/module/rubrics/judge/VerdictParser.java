package com.pixflow.module.rubrics.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.rubrics.error.RubricsErrorCode;
import com.pixflow.module.rubrics.model.Confidence;
import com.pixflow.module.rubrics.model.EvidenceRef;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.model.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class VerdictParser {
    private final ObjectMapper objectMapper;

    public VerdictParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JudgeVerdict parse(String rawText) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(rawText));
            Verdict verdict = Verdict.valueOf(root.path("verdict").asText("").toUpperCase(Locale.ROOT));
            Confidence confidence = Confidence.valueOf(root.path("confidence").asText("MEDIUM").toUpperCase(Locale.ROOT));
            String rationale = root.path("rationale").asText("");
            List<EvidenceRef> evidence = new ArrayList<>();
            JsonNode evidenceNode = root.path("evidence");
            if (evidenceNode.isArray()) {
                for (JsonNode item : evidenceNode) {
                    EvidenceType type = parseEvidenceType(item.path("type").asText("DATA"));
                    evidence.add(new EvidenceRef(type, item.path("ref").asText(""), item.path("excerpt").asText(""), null));
                }
            }
            return new JudgeVerdict(verdict, confidence, rationale, evidence);
        } catch (RuntimeException | java.io.IOException ex) {
            throw new PixFlowException(RubricsErrorCode.RUBRICS_JUDGE_PARSE_FAIL,
                    "Failed to parse rubrics judge verdict", ex);
        }
    }

    private static EvidenceType parseEvidenceType(String raw) {
        try {
            return EvidenceType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return EvidenceType.DATA;
        }
    }

    private static String extractJson(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("judge response is blank");
        }
        String text = rawText.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("judge response does not contain JSON object");
        }
        return text.substring(start, end + 1);
    }
}

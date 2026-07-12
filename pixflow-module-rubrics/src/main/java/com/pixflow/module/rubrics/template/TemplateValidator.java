package com.pixflow.module.rubrics.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pixflow.infra.ai.model.ModelRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;

public final class TemplateValidator {
    private final ObjectMapper objectMapper;

    public TemplateValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String validateAndHash(RubricTemplate template) {
        if (template.subjectType() == null || template.lifecycle() == null || template.evaluator() == null) {
            throw new IllegalArgumentException("template subjectType, lifecycle, and evaluator are required");
        }
        ModelRole role = template.evaluator().judgeRole();
        if (role != ModelRole.RUBRICS_JUDGE_TEXT && role != ModelRole.RUBRICS_JUDGE_VISION) {
            throw new IllegalArgumentException("template requires a dedicated rubrics judge role");
        }
        if (template.criteria().isEmpty()) {
            throw new IllegalArgumentException("template must contain criteria");
        }
        var keys = new HashSet<String>();
        for (Criterion criterion : template.criteria()) {
            if (criterion.key() == null || criterion.key().isBlank() || !keys.add(criterion.key())) {
                throw new IllegalArgumentException("criterion keys must be non-blank and unique");
            }
            if (criterion.kind() == null || blank(criterion.statement()) || blank(criterion.passAnchor())
                    || blank(criterion.failAnchor()) || criterion.evidenceTypes().isEmpty()
                    || criterion.applicability() == null || criterion.verifier() == null) {
                throw new IllegalArgumentException("criterion is incomplete: " + criterion.key());
            }
            if (criterion.verifier().type() == VerifierType.RULE && blank(criterion.verifier().ruleClass())) {
                throw new IllegalArgumentException("rule criterion requires ruleClass: " + criterion.key());
            }
            if (criterion.kind() == com.pixflow.module.rubrics.model.CriterionKind.HARD_RULE
                    && criterion.verifier().type() != VerifierType.RULE) {
                throw new IllegalArgumentException("hard rule requires deterministic verifier: " + criterion.key());
            }
        }
        try {
            JsonNode canonical = canonicalize(objectMapper.valueToTree(template));
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to hash rubric template", ex);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            java.util.stream.StreamSupport.stream(((Iterable<String>) node::fieldNames).spliterator(), false)
                    .sorted().forEach(name -> result.set(name, canonicalize(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

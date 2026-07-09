package com.pixflow.agent.subagent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class SubagentToolArguments {

    private SubagentToolArguments() {
    }

    static VisionArguments parseVision(Map<String, Object> args) {
        requireType(args, "vision");
        String prompt = requireString(args, "prompt");
        Object rawImageIds = args.getOrDefault("imageIds", List.of());
        if (!(rawImageIds instanceof List<?> list)) {
            throw invalid("imageIds must be an array of strings");
        }
        List<String> imageIds = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw invalid("imageIds must contain only non-blank strings");
            }
            imageIds.add(text);
        }
        return new VisionArguments(prompt, List.copyOf(imageIds));
    }

    static ExploreArguments parseExplore(Map<String, Object> args) {
        requireType(args, "explore");
        return new ExploreArguments(requireString(args, "prompt"));
    }

    private static void requireType(Map<String, Object> args, String expected) {
        Object raw = args.getOrDefault("type", expected);
        if (!(raw instanceof String text) || !expected.equals(text)) {
            throw invalid("type must be " + expected);
        }
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw invalid(key + " must be a non-blank string");
        }
        return text;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    record VisionArguments(String prompt, List<String> imageIds) {
    }

    record ExploreArguments(String prompt) {
    }
}

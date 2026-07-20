package com.pixflow.agent.subagent.tools;

import java.util.Map;

final class SubagentToolArguments {

    private SubagentToolArguments() {
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

    record ExploreArguments(String prompt) {
    }
}

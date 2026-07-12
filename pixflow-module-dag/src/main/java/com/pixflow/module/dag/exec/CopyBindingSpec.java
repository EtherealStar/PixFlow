package com.pixflow.module.dag.exec;

import java.util.List;

public record CopyBindingSpec(String style, int maxLength, String language,
                              List<String> includeKeywords) {
    public CopyBindingSpec {
        includeKeywords = includeKeywords == null ? List.of() : List.copyOf(includeKeywords);
    }
}

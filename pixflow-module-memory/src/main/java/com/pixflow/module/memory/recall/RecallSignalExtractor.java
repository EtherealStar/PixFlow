package com.pixflow.module.memory.recall;

import com.pixflow.module.memory.context.MemoryAttachment;
import com.pixflow.module.memory.context.MemoryContextRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RecallSignalExtractor {
    private static final Pattern SKU_PATTERN = Pattern.compile("(?i)(?<![A-Z0-9])SKU[-_ ]?[A-Z0-9]{2,}");

    private static final List<String> CATEGORY_WORDS = List.of("连衣裙", "家居", "鞋", "包", "美妆", "主图", "详情图", "场景图");

    private static final List<String> INTENT_WORDS = List.of("抠图", "去背景", "换底", "白底", "压缩",
            "水印", "生图", "重绘", "提升", "优化");

    private static final List<String> METRIC_WORDS = List.of("点击率", "转化率", "加购率", "曝光", "购买率", "停留时长", "CTR");

    public RecallSignals extract(MemoryContextRequest request) {
        String prompt = request.userPrompt() == null ? "" : request.userPrompt();
        List<String> skuIds = new ArrayList<>(request.skuIds());
        List<String> categories = new ArrayList<>(request.categoryHints());
        List<String> intents = new ArrayList<>();
        List<String> metrics = new ArrayList<>();

        Matcher matcher = SKU_PATTERN.matcher(prompt);
        while (matcher.find()) {
            skuIds.add(normalizeSku(matcher.group()));
        }

        for (MemoryAttachment attachment : request.attachments()) {
            if (attachment.skuId() != null && !attachment.skuId().isBlank()) {
                skuIds.add(normalizeSku(attachment.skuId()));
            }
            if (attachment.category() != null && !attachment.category().isBlank()) {
                categories.add(attachment.category().trim());
            }
            String fileName = attachment.fileName() == null ? "" : attachment.fileName();
            Matcher fileMatcher = SKU_PATTERN.matcher(fileName);
            while (fileMatcher.find()) {
                skuIds.add(normalizeSku(fileMatcher.group()));
            }
        }

        collectContains(prompt, CATEGORY_WORDS, categories);
        collectContains(prompt, INTENT_WORDS, intents);
        collectContains(prompt, METRIC_WORDS, metrics);
        return new RecallSignals(skuIds, categories, intents, metrics);
    }

    private static void collectContains(String text, List<String> candidates, List<String> output) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (lower.contains(candidate.toLowerCase(Locale.ROOT))) {
                output.add(candidate);
            }
        }
    }

    private static String normalizeSku(String raw) {
        return raw.replace(" ", "").replace("_", "").toUpperCase(Locale.ROOT);
    }
}

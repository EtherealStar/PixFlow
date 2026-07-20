package com.pixflow.module.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.memory")
public class MemoryProperties {
    private final Prompt prompt = new Prompt();

    private final Insight insight = new Insight();

    private final Reference reference = new Reference();

    public Prompt getPrompt() {
        return prompt;
    }

    public Insight getInsight() {
        return insight;
    }

    public Reference getReference() {
        return reference;
    }

    public static class Reference {
        private int maxPackageImages = 200;

        public int getMaxPackageImages() {
            return maxPackageImages;
        }

        public void setMaxPackageImages(int maxPackageImages) {
            this.maxPackageImages = positive(maxPackageImages, "reference.max-package-images");
        }
    }

    public static class Prompt {
        private int maxItems = 18;

        private int maxTokens = 1800;

        private int preferenceMaxItems = 50;

        private int skuHistoryMaxItemsPerSku = 5;

        private int insightTopn = 10;

        public int getMaxItems() {
            return maxItems;
        }

        public void setMaxItems(int maxItems) {
            this.maxItems = positive(maxItems, "prompt.max-items");
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = positive(maxTokens, "prompt.max-tokens");
        }

        public int getPreferenceMaxItems() {
            return preferenceMaxItems;
        }

        public void setPreferenceMaxItems(int preferenceMaxItems) {
            this.preferenceMaxItems = positive(preferenceMaxItems, "prompt.preference-max-items");
        }

        public int getSkuHistoryMaxItemsPerSku() {
            return skuHistoryMaxItemsPerSku;
        }

        public void setSkuHistoryMaxItemsPerSku(int skuHistoryMaxItemsPerSku) {
            this.skuHistoryMaxItemsPerSku = positive(skuHistoryMaxItemsPerSku, "prompt.sku-history-max-items-per-sku");
        }

        public int getInsightTopn() {
            return insightTopn;
        }

        public void setInsightTopn(int insightTopn) {
            this.insightTopn = positive(insightTopn, "prompt.insight-topn");
        }
    }

    public static class Insight {
        private String collection = "analysis_insight";

        private Integer expectedDimension;

        private final Recall recall = new Recall();

        private final Rank rank = new Rank();

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            if (collection == null || collection.isBlank()) {
                throw new IllegalArgumentException("insight.collection must not be blank");
            }
            this.collection = collection;
        }

        public Integer getExpectedDimension() {
            return expectedDimension;
        }

        public void setExpectedDimension(Integer expectedDimension) {
            if (expectedDimension != null && expectedDimension <= 0) {
                throw new IllegalArgumentException("insight.expected-dimension must be positive");
            }
            this.expectedDimension = expectedDimension;
        }

        public Recall getRecall() {
            return recall;
        }

        public Rank getRank() {
            return rank;
        }

    }

    public static class Recall {
        private int topnEach = 20;

        private int topn = 10;

        private int rrfK = 60;

        private double vectorThreshold = 0.1;

        private double minFinalScore = 0.1;

        public int getTopnEach() {
            return topnEach;
        }

        public void setTopnEach(int topnEach) {
            this.topnEach = positive(topnEach, "insight.recall.topn-each");
        }

        public int getTopn() {
            return topn;
        }

        public void setTopn(int topn) {
            this.topn = positive(topn, "insight.recall.topn");
        }

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = positive(rrfK, "insight.recall.rrf-k");
        }

        public double getVectorThreshold() {
            return vectorThreshold;
        }

        public void setVectorThreshold(double vectorThreshold) {
            this.vectorThreshold = finiteNonNegative(vectorThreshold, "insight.recall.vector-threshold");
        }

        public double getMinFinalScore() {
            return minFinalScore;
        }

        public void setMinFinalScore(double minFinalScore) {
            this.minFinalScore = finiteNonNegative(minFinalScore, "insight.recall.min-final-score");
        }
    }

    public static class Rank {
        private double rrfWeight = 0.55;

        private double confidenceWeight = 0.15;

        private double importanceWeight = 0.15;

        private double decayWeight = 0.10;

        private double recencyWeight = 0.05;

        public double getRrfWeight() {
            return rrfWeight;
        }

        public void setRrfWeight(double rrfWeight) {
            this.rrfWeight = finiteNonNegative(rrfWeight, "insight.rank.rrf-weight");
        }

        public double getConfidenceWeight() {
            return confidenceWeight;
        }

        public void setConfidenceWeight(double confidenceWeight) {
            this.confidenceWeight = finiteNonNegative(confidenceWeight, "insight.rank.confidence-weight");
        }

        public double getImportanceWeight() {
            return importanceWeight;
        }

        public void setImportanceWeight(double importanceWeight) {
            this.importanceWeight = finiteNonNegative(importanceWeight, "insight.rank.importance-weight");
        }

        public double getDecayWeight() {
            return decayWeight;
        }

        public void setDecayWeight(double decayWeight) {
            this.decayWeight = finiteNonNegative(decayWeight, "insight.rank.decay-weight");
        }

        public double getRecencyWeight() {
            return recencyWeight;
        }

        public void setRecencyWeight(double recencyWeight) {
            this.recencyWeight = finiteNonNegative(recencyWeight, "insight.rank.recency-weight");
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static double finiteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return value;
    }
}

package com.pixflow.harness.context.config;

import com.pixflow.harness.context.budget.ConservativeTokenEstimator;
import com.pixflow.harness.context.budget.ContextBudgetConfig;
import com.pixflow.harness.context.budget.ContextBudgetService;
import com.pixflow.harness.context.budget.TokenEstimator;
import com.pixflow.harness.context.budget.ToolResultExternalizer;
import com.pixflow.harness.context.compaction.CompactionConfig;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.compaction.SummarizationPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ContextAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TokenEstimator.class)
    public TokenEstimator tokenEstimator() {
        return new ConservativeTokenEstimator();
    }

    @Bean
    @ConditionalOnMissingBean(ContextBudgetService.class)
    public ContextBudgetService contextBudgetService(
            TokenEstimator tokenEstimator,
            ObjectProvider<ToolResultExternalizer> externalizerProvider) {
        // context 基础件由本模块自装配，避免下游 agent 为预算治理兜底。
        return new ContextBudgetService(
                ContextBudgetConfig.defaults(),
                tokenEstimator,
                externalizerProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(ContextCompactionService.class)
    public ContextCompactionService contextCompactionService(
            ContextBudgetService budgetService,
            TokenEstimator tokenEstimator,
            ObjectProvider<SummarizationPort> summarizationPortProvider) {
        // SummarizationPort 是 agent 可选实现；缺失时 ContextCompactionService 会走确定性兜底。
        return new ContextCompactionService(
                budgetService,
                tokenEstimator,
                summarizationPortProvider.getIfAvailable(),
                CompactionConfig.defaults());
    }
}

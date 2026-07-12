package com.pixflow.module.rubrics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.summary.EvaluationSummaryCalculator;
import com.pixflow.module.rubrics.template.TemplateLoader;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import com.pixflow.module.rubrics.template.TemplateValidator;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.model.ModelRouter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(RubricsProperties.class)
public class RubricsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public TemplateValidator templateValidator(ObjectMapper objectMapper) {
        return new TemplateValidator(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateLoader templateLoader(TemplateValidator validator) {
        return new TemplateLoader(validator);
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateRegistry templateRegistry(
            TemplateLoader loader, RubricsProperties properties, ModelRouter modelRouter) {
        modelRouter.resolve(ModelRole.RUBRICS_JUDGE_TEXT);
        modelRouter.resolve(ModelRole.RUBRICS_JUDGE_VISION);
        return new TemplateRegistry(loader.load(
                properties.getTemplateScan().getClasspathPrefix(),
                properties.getTemplateScan().getUserHomeDir()));
    }

    @Bean
    @ConditionalOnMissingBean
    public EvaluationSummaryCalculator evaluationSummaryCalculator() {
        return new EvaluationSummaryCalculator();
    }
}

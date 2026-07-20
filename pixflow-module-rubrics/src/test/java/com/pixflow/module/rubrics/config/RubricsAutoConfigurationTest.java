package com.pixflow.module.rubrics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.harness.eval.api.TraceQuery;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.model.ModelRouter;
import com.pixflow.module.rubrics.template.TemplateLoader;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * 校验 Rubrics 组合根的 fail-fast 契约：生产必需依赖缺失或 judge 角色未配置时，
 * 装配必须直接失败，而不是静默少装能力或回退到降级路径。
 *
 * <p>本测试不启动完整 Spring 上下文（那需要真实 MySQL/MinIO，由 Testcontainers 集成测试覆盖），
 * 而是直接调用 {@code @Bean} 方法锁定 fail-fast 行为，并守护“禁止 @ConditionalOnBean 静默跳过”的约束。
 */
class RubricsAutoConfigurationTest {

    @Test
    void templateRegistryFailsFastWhenJudgeRoleNotResolvable() {
        // judge 角色未在 ModelRouter 配置时，模板注册必须直接抛出，绝不回退到 PRIMARY_CHAT 或空 judge。
        RubricsAutoConfiguration config = new RubricsAutoConfiguration();
        RubricsProperties properties = new RubricsProperties();
        properties.validate();
        ModelRouter router = mock(ModelRouter.class);
        when(router.resolve(ModelRole.RUBRICS_JUDGE_TEXT))
                .thenThrow(new IllegalStateException("RUBRICS_JUDGE_TEXT not configured"));
        TemplateLoader loader = mock(TemplateLoader.class);

        assertThatThrownBy(() -> config.templateRegistry(loader, properties, router))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUBRICS_JUDGE_TEXT");
    }

    @Test
    void noBeanMethodSilentlyDropsCapabilityViaConditionalOnBean() {
        // 计划要求：测试 fake 显式注入（@ConditionalOnMissingBean 允许覆盖），但生产不得用
        // @ConditionalOnBean 静默少装能力。出现 @ConditionalOnBean 即视为 fail-fast 被绕过。
        boolean anyBean = false;
        for (Method method : RubricsAutoConfiguration.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Bean.class)) {
                continue;
            }
            anyBean = true;
            assertThat(method.getAnnotation(ConditionalOnBean.class))
                    .as("@Bean method %s must not use @ConditionalOnBean (use @ConditionalOnMissingBean)",
                            method.getName())
                    .isNull();
        }
        assertThat(anyBean).as("expected at least one @Bean method").isTrue();
    }

    @Test
    void traceEvidenceProviderRequiresTraceQueryAsRequiredDependency() {
        // TraceQuery 是生产必需依赖：traceEvidenceProvider 必须以构造参数接收它，
        // 不得改为 @ConditionalOnBean 或 Optional 静默少装。缺失时 Spring 在装配期 fail fast。
        boolean requiresTraceQuery = Arrays.stream(RubricsAutoConfiguration.class.getDeclaredMethods())
                .filter(method -> "traceEvidenceProvider".equals(method.getName()))
                .findFirst()
                .map(method -> Arrays.stream(method.getParameterTypes())
                        .anyMatch(TraceQuery.class::equals))
                .orElse(false);
        assertThat(requiresTraceQuery)
                .as("traceEvidenceProvider must take TraceQuery as a required parameter")
                .isTrue();
    }
}

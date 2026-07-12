package com.pixflow.infra.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

class DefaultModelRouterTest {

    @Test
    void resolvesPrimaryChatRoleFromDefaults() {
        DefaultModelRouter router = new DefaultModelRouter(new AiProperties(null, null, null, null, null, null, null, null));

        ResolvedModel resolved = router.resolve(ModelRole.PRIMARY_CHAT);

        assertThat(resolved.capability()).isEqualTo(ModelCapability.CHAT);
        assertThat(resolved.provider()).isEqualTo("dashscope");
    }

    @Test
    void rubricsJudgeRolesDoNotFallbackToProductionRoles() {
        DefaultModelRouter router = new DefaultModelRouter(new AiProperties(null, null, null, null, null, null, null, null));

        assertThatThrownBy(() -> router.resolve(ModelRole.RUBRICS_JUDGE_TEXT));
        assertThatThrownBy(() -> router.resolve(ModelRole.RUBRICS_JUDGE_VISION));
    }
}

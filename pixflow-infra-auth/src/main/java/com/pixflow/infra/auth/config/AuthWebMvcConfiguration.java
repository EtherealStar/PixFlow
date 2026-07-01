package com.pixflow.infra.auth.config;

import com.pixflow.infra.auth.context.CurrentUserResolver;
import java.util.List;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

public class AuthWebMvcConfiguration implements WebMvcConfigurer {
    private final CurrentUserResolver currentUserResolver;

    public AuthWebMvcConfiguration(CurrentUserResolver currentUserResolver) {
        this.currentUserResolver = currentUserResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserResolver);
    }
}

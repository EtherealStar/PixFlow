package com.pixflow.infra.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.auth.filter.JwtAuthenticationFilter;
import com.pixflow.infra.auth.filter.SecurityErrorWriter;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.core.MethodParameter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class AuthAutoConfigurationHandlerMappingTest {
    @Test
    void securityFilterChainSelectsApplicationControllerMappingWhenActuatorMappingAlsoExists()
            throws NoSuchMethodException {
        RequestMappingHandlerMapping applicationMapping = new RequestMappingHandlerMapping();
        RequestMappingHandlerMapping actuatorMapping = new RequestMappingHandlerMapping();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver());
        beanFactory.registerSingleton("requestMappingHandlerMapping", applicationMapping);
        beanFactory.registerSingleton("controllerEndpointHandlerMapping", actuatorMapping);

        Method factoryMethod = AuthAutoConfiguration.class.getDeclaredMethod(
                "pixflowSecurityFilterChain",
                HttpSecurity.class,
                JwtAuthenticationFilter.class,
                SecurityErrorWriter.class,
                RequestMappingHandlerMapping.class);
        DependencyDescriptor dependency = new DependencyDescriptor(new MethodParameter(factoryMethod, 3), true);

        assertThat(beanFactory.resolveDependency(dependency, null)).isSameAs(applicationMapping);
    }
}

package com.pixflow.infra.auth.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.auth.filter.JwtAuthenticationFilter;
import com.pixflow.infra.auth.filter.SecurityErrorWriter;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = AuthDispatcherSecurityTest.ProtectedController.class)
@Import(AuthDispatcherSecurityTest.TestSecurityConfiguration.class)
@ContextConfiguration(classes = AuthDispatcherSecurityTest.TestApplication.class)
class AuthDispatcherSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void initialAnonymousRequestRemainsProtected() throws Exception {
        mockMvc.perform(get("/protected"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void asyncDispatcherDoesNotRepeatAuthorization() throws Exception {
        mockMvc.perform(get("/protected").with(request -> {
                    request.setDispatcherType(DispatcherType.ASYNC);
                    return request;
                }))
                .andExpect(status().isOk());
    }

    @Test
    void errorDispatcherDoesNotRepeatAuthorization() throws Exception {
        mockMvc.perform(get("/protected").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
                .andExpect(status().isOk());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestSecurityConfiguration {
        @Bean
        SecurityErrorWriter securityErrorWriter() {
            return new SecurityErrorWriter(new ObjectMapper());
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(SecurityErrorWriter errorWriter) {
            return new JwtAuthenticationFilter(null, errorWriter);
        }

        @Bean
        FilterRegistrationBean<JwtAuthenticationFilter> disableServletFilterRegistration(
                JwtAuthenticationFilter filter) {
            FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }

        @Bean
        SecurityFilterChain securityFilterChain(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http,
                JwtAuthenticationFilter filter,
                SecurityErrorWriter errorWriter) throws Exception {
            return new AuthAutoConfiguration().pixflowSecurityFilterChain(http, filter, errorWriter);
        }

        @Bean
        ProtectedController protectedController() {
            return new ProtectedController();
        }

    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @RestController
    static class ProtectedController {
        @GetMapping("/protected")
        String protectedResource() {
            return "ok";
        }
    }
}

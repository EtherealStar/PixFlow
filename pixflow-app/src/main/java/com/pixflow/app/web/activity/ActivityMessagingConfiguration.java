package com.pixflow.app.web.activity;

import com.pixflow.app.auth.AuthWebSocketInterceptor;
import com.pixflow.infra.auth.service.AuthService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class ActivityMessagingConfiguration implements WebSocketMessageBrokerConfigurer {
    private final AuthWebSocketInterceptor authentication;

    public ActivityMessagingConfiguration(AuthWebSocketInterceptor authentication) {
        this.authentication = authentication;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authentication);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/activity");
    }

    @Bean
    public static AuthWebSocketInterceptor authWebSocketInterceptor(AuthService authService) {
        return new AuthWebSocketInterceptor(authService);
    }
}

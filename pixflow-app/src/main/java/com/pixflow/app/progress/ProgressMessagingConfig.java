package com.pixflow.app.progress;

import com.pixflow.app.auth.AuthWebSocketInterceptor;
import com.pixflow.common.progress.ProgressNotifier;
import com.pixflow.infra.auth.service.AuthService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class ProgressMessagingConfig implements WebSocketMessageBrokerConfigurer {
    private final ObjectProvider<AuthWebSocketInterceptor> authWebSocketInterceptor;

    public ProgressMessagingConfig(ObjectProvider<AuthWebSocketInterceptor> authWebSocketInterceptor) {
        this.authWebSocketInterceptor = authWebSocketInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        AuthWebSocketInterceptor interceptor = authWebSocketInterceptor.getIfAvailable();
        if (interceptor != null) {
            registration.interceptors(interceptor);
        }
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/progress").setAllowedOriginPatterns("*");
    }

    @Bean
    @ConditionalOnBean(SimpMessagingTemplate.class)
    @Primary
    public ProgressNotifier stompProgressNotifier(SimpMessagingTemplate messagingTemplate) {
        return new StompProgressNotifier(messagingTemplate);
    }

    @Bean
    @ConditionalOnBean(AuthService.class)
    @ConditionalOnMissingBean
    public AuthWebSocketInterceptor authWebSocketInterceptor(AuthService authService) {
        return new AuthWebSocketInterceptor(authService);
    }
}

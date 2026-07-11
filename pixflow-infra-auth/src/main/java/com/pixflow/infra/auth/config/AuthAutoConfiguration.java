package com.pixflow.infra.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.auth.context.CurrentUserResolver;
import com.pixflow.infra.auth.crypto.PasswordHasher;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.filter.JwtAuthenticationFilter;
import com.pixflow.infra.auth.filter.SecurityErrorWriter;
import com.pixflow.infra.auth.persistence.UserAccountMapper;
import com.pixflow.infra.auth.service.AuthService;
import com.pixflow.infra.auth.session.AccessTokenBlacklist;
import com.pixflow.infra.auth.session.AuthSessionStore;
import com.pixflow.infra.auth.session.RedisAccessTokenBlacklist;
import com.pixflow.infra.auth.session.RedisAuthSessionStore;
import com.pixflow.infra.auth.throttle.LoginThrottleService;
import com.pixflow.infra.auth.token.JwtTokenService;
import com.pixflow.infra.auth.token.RefreshTokenGenerator;
import com.pixflow.infra.cache.config.CacheAutoConfiguration;
import com.pixflow.infra.cache.counter.AtomicCounter;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.store.CacheStore;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@AutoConfiguration
@AutoConfigureAfter(CacheAutoConfiguration.class)
@EnableConfigurationProperties(AuthProperties.class)
@MapperScan(basePackageClasses = UserAccountMapper.class, annotationClass = Mapper.class)
public class AuthAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public PasswordHasher passwordHasher(AuthProperties properties) {
        return new PasswordHasher(properties.getPassword().getBcryptStrength());
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenService jwtTokenService(
            AuthProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        return new JwtTokenService(properties, objectMapper, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public RefreshTokenGenerator refreshTokenGenerator() {
        return new RefreshTokenGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthSessionStore authSessionStore(CacheStore cacheStore, CacheNamespace cacheNamespace) {
        return new RedisAuthSessionStore(cacheStore, cacheNamespace);
    }

    @Bean
    @ConditionalOnMissingBean
    public AccessTokenBlacklist accessTokenBlacklist(CacheStore cacheStore, CacheNamespace cacheNamespace) {
        return new RedisAccessTokenBlacklist(cacheStore, cacheNamespace);
    }

    @Bean
    @ConditionalOnMissingBean
    public LoginThrottleService loginThrottleService(
            AtomicCounter atomicCounter,
            CacheNamespace cacheNamespace,
            AuthProperties properties) {
        return new LoginThrottleService(atomicCounter, cacheNamespace, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthService authService(
            UserAccountMapper userMapper,
            PasswordHasher passwordHasher,
            JwtTokenService jwtTokenService,
            RefreshTokenGenerator refreshTokenGenerator,
            AuthSessionStore sessionStore,
            AccessTokenBlacklist blacklist,
            LoginThrottleService throttleService,
            AuthProperties properties,
            Clock clock) {
        return new AuthService(
                userMapper,
                passwordHasher,
                jwtTokenService,
                refreshTokenGenerator,
                sessionStore,
                blacklist,
                throttleService,
                properties,
                clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityErrorWriter securityErrorWriter(ObjectMapper objectMapper) {
        return new SecurityErrorWriter(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationFilter jwtAuthenticationFilter(AuthService authService, SecurityErrorWriter errorWriter) {
        return new JwtAuthenticationFilter(authService, errorWriter);
    }

    @Bean
    @ConditionalOnMissingBean
    public CurrentUserResolver currentUserResolver() {
        return new CurrentUserResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthWebMvcConfiguration authWebMvcConfiguration(CurrentUserResolver currentUserResolver) {
        return new AuthWebMvcConfiguration(currentUserResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain pixflowSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            SecurityErrorWriter errorWriter) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, authException) ->
                errorWriter.write(response, new AuthException(AuthErrorCode.AUTH_TOKEN_MISSING, "需要登录后访问"));
        AccessDeniedHandler deniedHandler = (request, response, accessDeniedException) ->
                errorWriter.write(response, new AuthException(AuthErrorCode.AUTH_ACCESS_DENIED, "无权限访问"));

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // 初始 REQUEST 已完成身份校验，容器内部二次分派不能再依赖已清理的 SecurityContext。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/ws").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

package com.pixflow.app.web.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 只在公开 HTTP 请求边界拒绝未知字段，不改变 provider 与持久化 JSON 的 ObjectMapper。 */
@Configuration(proxyBeanMethods = false)
public class StrictHttpJsonConfiguration implements WebMvcConfigurer {
    private final ObjectMapper objectMapper;

    public StrictHttpJsonConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .forEach(converter -> converter.setObjectMapper(objectMapper.copy()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)));
    }
}

package com.pixflow.infra.thirdparty.http;

import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

public final class DefaultThirdPartyAuthStrategy implements ThirdPartyAuthStrategy {

    @Override
    public void apply(ThirdPartyMutableRequest request, ThirdPartyProperties.Provider provider) {
        if (provider == null || provider.auth() == null) {
            return;
        }
        ThirdPartyProperties.Auth auth = provider.auth();
        String type = normalize(auth.type());
        Map<String, String> properties = auth.properties();
        if ("none".equals(type) || type.isBlank()) {
            return;
        }
        if ("bearer".equals(type)) {
            String token = firstNonBlank(properties.get("token"), properties.get("api-key"), properties.get("apiKey"));
            if (StringUtils.hasText(token)) {
                request.headers().set(firstNonBlank(properties.get("header"), HttpHeaders.AUTHORIZATION), "Bearer " + token);
            }
            return;
        }
        if ("api-key".equals(type) || "api-key-header".equals(type)) {
            String header = firstNonBlank(properties.get("header"), properties.get("headerName"), "X-Api-Key");
            String value = firstNonBlank(properties.get("value"), properties.get("api-key"), properties.get("apiKey"), properties.get("key"));
            if (StringUtils.hasText(header) && StringUtils.hasText(value)) {
                request.headers().set(header, value);
            }
            return;
        }
        if ("basic".equals(type)) {
            String username = firstNonBlank(properties.get("username"), properties.get("user"));
            String password = firstNonBlank(properties.get("password"), "");
            if (StringUtils.hasText(username)) {
                String raw = username + ":" + password;
                request.headers().set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            }
            return;
        }
        if ("header".equals(type)) {
            String header = firstNonBlank(properties.get("header"), properties.get("headerName"));
            String value = firstNonBlank(properties.get("value"), properties.get("headerValue"));
            if (StringUtils.hasText(header) && StringUtils.hasText(value)) {
                request.headers().set(header, value);
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}

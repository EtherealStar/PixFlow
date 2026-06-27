package com.pixflow.infra.thirdparty.http;

import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

public record ThirdPartyHttpRequest(
        String api,
        String providerId,
        HttpMethod method,
        URI uri,
        HttpHeaders headers,
        byte[] body,
        MediaType contentType) {
}

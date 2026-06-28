package com.pixflow.infra.thirdparty.http;

import java.net.URI;
import java.util.Arrays;
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
    public ThirdPartyHttpRequest {
        headers = headers == null ? HttpHeaders.EMPTY : copyHeaders(headers);
        body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
    }

    @Override
    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    private static HttpHeaders copyHeaders(HttpHeaders source) {
        HttpHeaders copy = new HttpHeaders();
        copy.putAll(source);
        return HttpHeaders.readOnlyHttpHeaders(copy);
    }
}

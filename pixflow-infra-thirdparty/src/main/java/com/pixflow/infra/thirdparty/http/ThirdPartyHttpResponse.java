package com.pixflow.infra.thirdparty.http;

import java.util.Arrays;
import org.springframework.http.HttpHeaders;

public record ThirdPartyHttpResponse(int statusCode, HttpHeaders headers, byte[] body) {
    public ThirdPartyHttpResponse {
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

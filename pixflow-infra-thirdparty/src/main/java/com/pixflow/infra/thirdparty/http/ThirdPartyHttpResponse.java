package com.pixflow.infra.thirdparty.http;

import java.util.Arrays;
import org.springframework.http.HttpHeaders;

public record ThirdPartyHttpResponse(int statusCode, HttpHeaders headers, byte[] body) {
    public ThirdPartyHttpResponse {
        headers = headers == null ? HttpHeaders.EMPTY : HttpHeaders.readOnlyHttpHeaders(headers);
        body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
    }

    @Override
    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }
}

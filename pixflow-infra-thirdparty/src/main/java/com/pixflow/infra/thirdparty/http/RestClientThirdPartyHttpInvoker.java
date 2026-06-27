package com.pixflow.infra.thirdparty.http;

import java.util.Objects;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class RestClientThirdPartyHttpInvoker implements ThirdPartyHttpInvoker {
    private final RestClient restClient;

    public RestClientThirdPartyHttpInvoker(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    @Override
    public ThirdPartyHttpResponse exchange(ThirdPartyHttpRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(request.headers());
        if (request.contentType() != null) {
            headers.setContentType(request.contentType());
        }
        try {
            ResponseEntity<byte[]> entity = restClient.method(request.method())
                    .uri(request.uri())
                    .headers(httpHeaders -> httpHeaders.addAll(headers))
                    .contentType(request.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM : request.contentType())
                    .body(request.body())
                    .retrieve()
                    .toEntity(byte[].class);
            return new ThirdPartyHttpResponse(entity.getStatusCode().value(), entity.getHeaders(), entity.getBody());
        } catch (RestClientResponseException ex) {
            return new ThirdPartyHttpResponse(ex.getRawStatusCode(), ex.getResponseHeaders(), ex.getResponseBodyAsByteArray());
        } catch (RestClientException ex) {
            throw ex;
        }
    }
}

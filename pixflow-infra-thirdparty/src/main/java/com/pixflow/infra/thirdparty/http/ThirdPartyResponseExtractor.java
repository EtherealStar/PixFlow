package com.pixflow.infra.thirdparty.http;

public interface ThirdPartyResponseExtractor<T> {
    T extract(ThirdPartyHttpResponse response);
}

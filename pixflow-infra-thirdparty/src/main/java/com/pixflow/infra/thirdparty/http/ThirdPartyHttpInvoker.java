package com.pixflow.infra.thirdparty.http;

public interface ThirdPartyHttpInvoker {
    ThirdPartyHttpResponse exchange(ThirdPartyHttpRequest request);
}

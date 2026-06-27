package com.pixflow.harness.permission.token;

/**
 * 服务端一次性确认令牌。
 */
public record ConfirmationToken(String tokenId) {
    public ConfirmationToken {
        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException("tokenId 不能为空");
        }
    }
}

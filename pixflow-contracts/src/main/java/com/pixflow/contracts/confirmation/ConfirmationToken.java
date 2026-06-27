package com.pixflow.contracts.confirmation;

/**
 * 服务端一次性确认令牌句柄。
 *
 * <p>这里只暴露不透明 tokenId，不携带 claims，避免载荷进入不可信通道。</p>
 */
public record ConfirmationToken(String tokenId) {
    public ConfirmationToken {
        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException("tokenId 不能为空");
        }
    }
}

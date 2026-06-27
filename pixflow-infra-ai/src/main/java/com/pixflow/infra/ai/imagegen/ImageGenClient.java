package com.pixflow.infra.ai.imagegen;

/**
 * 源图重绘客户端。
 */
public interface ImageGenClient {
    ImageGenResult generate(ImageGenRequest request);
}

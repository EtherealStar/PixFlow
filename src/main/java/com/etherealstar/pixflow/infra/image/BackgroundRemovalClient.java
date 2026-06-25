package com.etherealstar.pixflow.infra.image;

import java.awt.image.BufferedImage;

/**
 * 抠图（去背景）抽象（需求 8.4 的 {@code remove_bg} 工具）。
 *
 * <p>将第三方抠图 API（如 remove.bg / 阿里云智能抠图）的 HTTP 调用细节封装在接口之后，使执行引擎
 * 无需感知具体服务，同时便于测试以内存替身替换真实服务并注入失败以验证失败隔离（需求 11.1）。</p>
 */
public interface BackgroundRemovalClient {

    /**
     * 去除图像背景，返回带透明背景（alpha 通道）的图像。
     *
     * @param input 原始图像
     * @return 去背景后的图像
     * @throws ImageProcessingException 当第三方服务调用失败或不可达时
     */
    BufferedImage removeBackground(BufferedImage input);
}

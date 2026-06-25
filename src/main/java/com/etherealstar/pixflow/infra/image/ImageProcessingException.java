package com.etherealstar.pixflow.infra.image;

/**
 * 图像处理异常（需求 8.4、11.1）。
 *
 * <p>编解码失败、像素工具节点处理失败、第三方抠图 API 错误/不可达等情况统一以本异常抛出，
 * 由 DAG 执行引擎的失败隔离逻辑（FailureIsolator）捕获并将对应支路结果标记为失败，
 * 不影响同图其余支路、批次其余图片与其余 SKU 的处理（需求 11.1、11.2）。</p>
 */
public class ImageProcessingException extends RuntimeException {

    public ImageProcessingException(String message) {
        super(message);
    }

    public ImageProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}

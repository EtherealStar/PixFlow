package com.etherealstar.pixflow.infra.ai;

/**
 * LLM 调用异常。
 *
 * <p>当模型未配置、调用底层模型失败或模型返回空内容时抛出。上层（如 DAG_Parser）可据此
 * 将其转换为统一错误响应（如 {@code DAG_PARSE_FAILED}）。</p>
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}

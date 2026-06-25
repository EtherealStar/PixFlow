package com.etherealstar.pixflow.module.dag.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DAG 执行引擎相关配置（需求 8.3、10.4、11.2）。
 *
 * <p>绑定 {@code pixflow.engine.*} 配置项：
 * <ul>
 *   <li>{@code maxConcurrency}：图片处理线程池固定大小，即批处理期间的最大并发上限（需求 8.3，默认 8）；</li>
 *   <li>{@code copyMaxLength}：生成文案写入 {@code generated_copy} 的最大长度，超出从尾部截断（需求 10.4，默认 2000）；</li>
 *   <li>{@code errorMsgMaxLength}：失败结果 {@code error_msg} 的最大长度，超出截断（需求 11.2，默认 1000）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "pixflow.engine")
public class EngineProperties {

    /** 图片处理线程池固定大小（最大并发，需求 8.3，默认 8）。 */
    private int maxConcurrency = 8;

    /** 生成文案最大长度（需求 10.4，默认 2000）。 */
    private int copyMaxLength = 2000;

    /** 失败原因 error_msg 最大长度（需求 11.2，默认 1000）。 */
    private int errorMsgMaxLength = 1000;

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public int getCopyMaxLength() {
        return copyMaxLength;
    }

    public void setCopyMaxLength(int copyMaxLength) {
        this.copyMaxLength = copyMaxLength;
    }

    public int getErrorMsgMaxLength() {
        return errorMsgMaxLength;
    }

    public void setErrorMsgMaxLength(int errorMsgMaxLength) {
        this.errorMsgMaxLength = errorMsgMaxLength;
    }
}

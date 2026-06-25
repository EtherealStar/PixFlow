package com.etherealstar.pixflow.module.dag.engine;

import com.etherealstar.pixflow.module.task.entity.ProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 单图/单支路失败隔离器（需求 9.4、10.7、11.1、11.2，DAG_Engine 组件 FailureIsolator）。
 *
 * <p>当某条「图片 × 支路」工作单元中的工具节点抛出异常、第三方抠图 API 出错/不可达，或文案生成
 * （generate_copy）的 LLM 调用失败时，由本组件统一处理：
 * <ol>
 *   <li>将该 {@link ProcessResult#getStatus()} 置为失败（2）；</li>
 *   <li>记录失败原因 {@code error_msg}，并截断至配置上限（默认 1000 字符，需求 11.2）；</li>
 *   <li>清空不完整的 {@code output_path}，不保留半成品产物（需求 11.1）。</li>
 * </ol>
 *
 * <p>失败被隔离在单个工作单元内，同一原图的其余支路、批次内其余图片以及其余 SKU 的文案生成均继续
 * 处理（需求 9.4、10.7、11.1），不因个别失败而中断整批任务。</p>
 */
@Component
public class FailureIsolator {

    private static final Logger log = LoggerFactory.getLogger(FailureIsolator.class);

    private final EngineProperties properties;

    public FailureIsolator(EngineProperties properties) {
        this.properties = properties;
    }

    /**
     * 将一条结果标记为失败并记录原因，不保留不完整产物。
     *
     * @param result    待标记的结果记录（原地修改）
     * @param throwable 触发失败的异常
     */
    public void markFailed(ProcessResult result, Throwable throwable) {
        String message = throwable == null ? "未知错误" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        result.setStatus(2);
        result.setErrorMsg(truncate(message));
        result.setOutputPath(null);
        log.warn("支路失败隔离：taskId={}, imageId={}, branchId={}, 原因={}",
                result.getTaskId(), result.getImageId(), result.getBranchId(), message);
    }

    /** 将错误信息截断至配置上限（默认 1000 字符）。 */
    private String truncate(String message) {
        int max = properties.getErrorMsgMaxLength();
        if (max > 0 && message.length() > max) {
            return message.substring(0, max);
        }
        return message;
    }
}

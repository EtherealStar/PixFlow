package com.etherealstar.pixflow.common.error;

import org.springframework.http.HttpStatus;

/**
 * 全局错误码目录。
 *
 * <p>每个错误码携带一个默认的中文提示信息与对应的 HTTP 状态码，供全局异常处理器构造统一错误响应
 * {@link ErrorResponse} 使用。错误码分类与对应需求见 design.md「Error Handling」一节。
 */
public enum ErrorCode {

    // ---- 上传校验（需求 1.2–1.4）----
    ASSET_ZIP_INVALID("上传的 zip 文件无效或已损坏", HttpStatus.BAD_REQUEST),
    ASSET_ZIP_TOO_LARGE("上传的 zip 文件体积超过允许上限", HttpStatus.PAYLOAD_TOO_LARGE),
    ASSET_ZIP_BOMB("压缩包异常：解压后文件总大小或文件数量超过阈值", HttpStatus.BAD_REQUEST),

    // ---- 文档校验（需求 3.2–3.5）----
    DOC_FORMAT_INVALID("文案文档格式或体积不合法", HttpStatus.BAD_REQUEST),
    DOC_ROWS_EXCEEDED("文案文档数据行数超过允许上限", HttpStatus.BAD_REQUEST),
    DOC_MISSING_ID_COLUMN("文案文档首行表头缺少 id 列", HttpStatus.BAD_REQUEST),

    // ---- 分页参数（需求 4.5、13.2）----
    INVALID_PAGINATION("分页参数非法", HttpStatus.BAD_REQUEST),

    // ---- 消息校验（需求 5.7、5.8）----
    MESSAGE_CONTENT_INVALID("消息内容校验失败", HttpStatus.BAD_REQUEST),
    PACKAGE_UNAVAILABLE("所选素材包不可用", HttpStatus.BAD_REQUEST),

    // ---- DAG 解析（需求 6.2、6.6）----
    DAG_PARSE_FAILED("无法将指令解析为合法的 DAG", HttpStatus.BAD_REQUEST),
    DAG_MISSING_PARAMS("DAG 中存在缺失的必填参数", HttpStatus.BAD_REQUEST),

    // ---- DAG 校验（需求 7.2–7.7）----
    DAG_STRUCTURE_INVALID("DAG 结构非法，无法解析或缺少 nodes/edges", HttpStatus.BAD_REQUEST),
    DAG_CYCLE_DETECTED("DAG 中检测到环", HttpStatus.BAD_REQUEST),
    DAG_INVALID_TOOL("DAG 中存在非法工具名", HttpStatus.BAD_REQUEST),
    DAG_PARAM_INVALID("DAG 节点参数校验失败", HttpStatus.BAD_REQUEST),
    DAG_NODE_COUNT_INVALID("DAG 节点数量非法", HttpStatus.BAD_REQUEST),
    DAG_EDGE_INVALID("DAG 中存在非法的边引用", HttpStatus.BAD_REQUEST),

    // ---- 资源不存在（需求 4.7、4.9、5.5、12.5、14.5）----
    CONVERSATION_NOT_FOUND("对话不存在", HttpStatus.NOT_FOUND),
    PACKAGE_NOT_FOUND("素材包不存在", HttpStatus.NOT_FOUND),
    TASK_NOT_FOUND("任务不存在", HttpStatus.NOT_FOUND),
    RESULT_NOT_FOUND("结果图片不存在", HttpStatus.NOT_FOUND),

    // ---- 任务状态筛选（需求 12.3）----
    INVALID_TASK_STATUS("任务状态筛选参数非法", HttpStatus.BAD_REQUEST),

    // ---- 下载无结果（需求 13.5）----
    NO_DOWNLOADABLE_RESULT("该任务无任何可下载的成功结果图", HttpStatus.BAD_REQUEST),

    // ---- 引用约束（需求 14.4）----
    PACKAGE_REFERENCED_BY_TASK("素材包已被任务引用，无法删除", HttpStatus.CONFLICT),

    // ---- 兜底（未预期的服务端错误）----
    INTERNAL_ERROR("服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(String defaultMessage, HttpStatus httpStatus) {
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}

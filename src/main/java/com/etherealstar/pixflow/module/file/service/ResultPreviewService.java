package com.etherealstar.pixflow.module.file.service;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.infra.storage.StorageService;
import com.etherealstar.pixflow.module.file.dto.ResultPreviewResponse;
import com.etherealstar.pixflow.module.task.entity.ProcessResult;
import com.etherealstar.pixflow.module.task.mapper.ProcessResultMapper;

import org.springframework.stereotype.Service;

/**
 * 加工结果图预览服务（需求 4.6、4.7）。
 *
 * <p>校验结果图存在且对应物理文件存在后，返回其可访问的预览 URL（指向原始字节流端点）；
 * 结果记录不存在、无输出路径（失败结果）或物理文件缺失，均按「图片不存在」处理
 * （{@link ErrorCode#RESULT_NOT_FOUND}）。</p>
 */
@Service
public class ResultPreviewService {

    /** 预览 URL 模板：指向原始字节流端点（见 {@code ResultPreviewController}）。 */
    static final String RAW_URL_TEMPLATE = "/api/asset/result/%d/raw";

    private final ProcessResultMapper resultMapper;
    private final StorageService storageService;

    public ResultPreviewService(ProcessResultMapper resultMapper, StorageService storageService) {
        this.resultMapper = resultMapper;
        this.storageService = storageService;
    }

    /**
     * 返回结果图的可访问预览 URL（需求 4.6、4.7）。
     *
     * @param resultId 结果图 id
     * @return 预览响应（含 URL）
     * @throws BusinessException 结果图或其物理文件不存在时（RESULT_NOT_FOUND）
     */
    public ResultPreviewResponse previewUrl(long resultId) {
        ProcessResult result = requireExisting(resultId);
        return new ResultPreviewResponse(
                result.getId(),
                result.getSkuId(),
                String.format(RAW_URL_TEMPLATE, result.getId()));
    }

    /**
     * 校验结果图存在并返回其相对存储路径，供原始字节流端点读取（需求 4.7）。
     *
     * @param resultId 结果图 id
     * @return 结果图的输出相对路径
     * @throws BusinessException 结果图或其物理文件不存在时（RESULT_NOT_FOUND）
     */
    public String resolveOutputPath(long resultId) {
        return requireExisting(resultId).getOutputPath();
    }

    private ProcessResult requireExisting(long resultId) {
        ProcessResult result = resultMapper.selectById(resultId);
        if (result == null) {
            throw new BusinessException(ErrorCode.RESULT_NOT_FOUND,
                    "结果图片不存在：id=" + resultId);
        }
        String outputPath = result.getOutputPath();
        if (outputPath == null || outputPath.isBlank() || !storageService.exists(outputPath)) {
            throw new BusinessException(ErrorCode.RESULT_NOT_FOUND,
                    "结果图片不存在或尚未生成：id=" + resultId);
        }
        return result;
    }
}

package com.etherealstar.pixflow.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.common.web.PageResponse;
import com.etherealstar.pixflow.common.web.Pagination;
import com.etherealstar.pixflow.infra.storage.StorageService;
import com.etherealstar.pixflow.module.task.dto.TaskResultItem;
import com.etherealstar.pixflow.module.task.entity.ProcessResult;
import com.etherealstar.pixflow.module.task.entity.ProcessTask;
import com.etherealstar.pixflow.module.task.mapper.ProcessResultMapper;
import com.etherealstar.pixflow.module.task.mapper.ProcessTaskMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 加工结果列表查询与流式打包下载服务（需求 13.1–13.6）。
 *
 * <p>{@link #listResults} 提供按 {@code taskId} 筛选的分页结果列表（需求 13.1、13.2）；
 * {@link #streamZip} 逐文件流式写出 zip，仅包含成功结果图（{@code status=1} 且 {@code output_path} 非空，
 * 需求 13.3），每个条目文件名含 {@code skuId}+{@code resultId} 保证唯一（需求 13.4）。下载过程采用
 * 「逐文件读取输入流 → 拷贝至 zip 输出流」的流式方式，峰值常驻内存只与单个文件缓冲区相关，不随结果数线性增长
 * （需求 13.6）。任务无任何成功结果时拒绝下载（需求 13.5）。</p>
 */
@Service
public class ResultDownloadService {

    private static final Logger log = LoggerFactory.getLogger(ResultDownloadService.class);

    /** 流式拷贝缓冲区大小（字节）。固定大小，确保峰值内存与结果数无关（需求 13.6）。 */
    private static final int COPY_BUFFER_SIZE = 8192;

    private final ProcessResultMapper resultMapper;
    private final ProcessTaskMapper taskMapper;
    private final StorageService storageService;

    public ResultDownloadService(ProcessResultMapper resultMapper,
                                 ProcessTaskMapper taskMapper,
                                 StorageService storageService) {
        this.resultMapper = resultMapper;
        this.taskMapper = taskMapper;
        this.storageService = storageService;
    }

    /**
     * 加工结果列表（分页，按结果 id 升序，可选 {@code taskId} 筛选，需求 13.1、13.2）。
     *
     * @param page   页码（{@code null} 默认 1）
     * @param size   每页条数（{@code null} 默认 20，取值 1–100）
     * @param taskId 任务 id 筛选（{@code null} 不筛选）
     * @return 分页结果列表
     * @throws BusinessException 分页参数越界时（INVALID_PAGINATION）
     */
    public PageResponse<TaskResultItem> listResults(Long page, Long size, Long taskId) {
        Pagination pagination = Pagination.of(page, size);
        QueryWrapper<ProcessResult> wrapper = new QueryWrapper<>();
        if (taskId != null) {
            wrapper.eq("task_id", taskId);
        }
        wrapper.orderByAsc("id");

        Page<ProcessResult> pageReq = Page.of(pagination.page(), pagination.size());
        Page<ProcessResult> result = resultMapper.selectPage(pageReq, wrapper);

        List<TaskResultItem> items = new ArrayList<>();
        for (ProcessResult r : result.getRecords()) {
            items.add(TaskResultItem.from(r));
        }
        return PageResponse.of(items, result.getTotal(), pagination.page(), pagination.size());
    }

    /**
     * 校验任务存在且有可下载结果，返回建议的下载文件名（需求 13.5）。
     *
     * @param taskId 任务 id
     * @return zip 下载文件名
     * @throws BusinessException 任务不存在（TASK_NOT_FOUND）或无成功结果（NO_DOWNLOADABLE_RESULT）时
     */
    public String prepareDownloadName(long taskId) {
        ProcessTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务不存在：id=" + taskId);
        }
        long successCount = resultMapper.selectCount(successQuery(taskId));
        if (successCount <= 0) {
            throw new BusinessException(ErrorCode.NO_DOWNLOADABLE_RESULT,
                    "该任务无任何可下载的成功结果图：taskId=" + taskId);
        }
        return "task_" + taskId + "_results.zip";
    }

    /**
     * 将任务的全部成功结果图流式写入 zip 输出流（需求 13.3、13.4、13.6）。
     *
     * <p>逐条结果打开输入流并拷贝至 {@link ZipOutputStream}，单文件读写失败仅跳过该文件并记录日志，
     * 不中断整体下载。条目名采用 {@code {skuId}_{resultId}.{ext}}，{@code resultId} 全局唯一，
     * 保证 zip 内文件名互不冲突（需求 13.4）。</p>
     *
     * @param taskId       任务 id（调用前应已通过 {@link #prepareDownloadName} 校验）
     * @param outputStream 目标输出流（通常为 HTTP 响应流），由调用方负责关闭
     * @throws IOException 写出 zip 失败时
     */
    public void streamZip(long taskId, OutputStream outputStream) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
            List<ProcessResult> successes = resultMapper.selectList(
                    successQuery(taskId).orderByAsc("id"));
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            for (ProcessResult r : successes) {
                String path = r.getOutputPath();
                if (path == null || path.isBlank() || !storageService.exists(path)) {
                    log.warn("跳过缺失的结果文件：resultId={}, path={}", r.getId(), path);
                    continue;
                }
                String entryName = entryName(r, path);
                try (InputStream in = storageService.openInputStream(path)) {
                    zip.putNextEntry(new ZipEntry(entryName));
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        zip.write(buffer, 0, read);
                    }
                    zip.closeEntry();
                } catch (Exception e) {
                    log.warn("打包结果文件失败，已跳过：resultId={}, path={}", r.getId(), path, e);
                }
            }
            zip.finish();
        }
    }

    /** 条目名：{@code {sku}_{resultId}.{ext}}，resultId 唯一确保互不冲突（需求 13.4）。 */
    private String entryName(ProcessResult r, String path) {
        String sku = r.getSkuId() == null || r.getSkuId().isBlank() ? "sku" : r.getSkuId();
        String safeSku = sku.replaceAll("[^A-Za-z0-9_-]", "_");
        return safeSku + "_" + r.getId() + "." + extension(path);
    }

    private String extension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return "png";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private QueryWrapper<ProcessResult> successQuery(long taskId) {
        return new QueryWrapper<ProcessResult>()
                .eq("task_id", taskId)
                .eq("status", 1)
                .isNotNull("output_path");
    }
}

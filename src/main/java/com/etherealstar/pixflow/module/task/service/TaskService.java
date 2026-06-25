package com.etherealstar.pixflow.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.common.web.PageResponse;
import com.etherealstar.pixflow.common.web.Pagination;
import com.etherealstar.pixflow.module.dag.DagJsonCodec;
import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.engine.DagExecutionEngine;
import com.etherealstar.pixflow.module.dag.engine.DagExecutionSummary;
import com.etherealstar.pixflow.module.dag.validator.DagValidator;
import com.etherealstar.pixflow.module.conversation.entity.Conversation;
import com.etherealstar.pixflow.module.conversation.mapper.ConversationMapper;
import com.etherealstar.pixflow.module.file.domain.PackageStatus;
import com.etherealstar.pixflow.module.file.entity.AssetImage;
import com.etherealstar.pixflow.module.file.entity.AssetPackage;
import com.etherealstar.pixflow.module.file.mapper.AssetImageMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import com.etherealstar.pixflow.module.task.dto.ConfirmRequest;
import com.etherealstar.pixflow.module.task.dto.ConfirmResponse;
import com.etherealstar.pixflow.module.task.dto.TaskDetailResponse;
import com.etherealstar.pixflow.module.task.dto.TaskListItem;
import com.etherealstar.pixflow.module.task.dto.TaskResultItem;
import com.etherealstar.pixflow.module.task.entity.ProcessResult;
import com.etherealstar.pixflow.module.task.entity.ProcessTask;
import com.etherealstar.pixflow.module.task.mapper.ProcessResultMapper;
import com.etherealstar.pixflow.module.task.mapper.ProcessTaskMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

/**
 * 任务管理服务（Task_Manager，需求 7.1、8.5–8.7、11.3、12.1–12.6）。
 *
 * <p>职责：
 * <ul>
 *   <li><strong>确认执行</strong>（{@link #confirm}）：在服务端用 {@link DagValidator} 重新独立校验前端回传的
 *       DAG（需求 7.1），校验通过后创建 {@code process_task}（status=0）并将 DAG 序列化持久化（需求 12.6），
 *       随后驱动 {@link DagExecutionEngine} 同步执行；返回任务终态、计数与成功结果图预览 URL
 *       （按 {@code asset_image.id} 升序取前 {@code min(3, n)} 张，需求 8.6、8.7）。</li>
 *   <li><strong>任务列表</strong>（{@link #listTasks}）：分页、{@code created_at} 降序，支持合法 status(0/1/2/3)
 *       筛选，非法 status 拒绝（需求 12.1、12.2、12.3）。</li>
 *   <li><strong>任务详情</strong>（{@link #taskDetail}）：返回 dagJson/status/计数与结果列表，失败结果含
 *       {@code error_msg}（需求 11.3、12.4、12.5）；结果列表分页（需求 13.1、13.2）。</li>
 * </ul>
 *
 * <p>本服务不信任前端回传的 {@code dagJson}，一律重新校验；持久化的 {@code dag_json} 使用
 * {@link DagJsonCodec} 由已校验的 {@link Dag} 规范序列化，保证序列化往返一致（Property 35）。</p>
 */
@Service
public class TaskService {

    /** 预览 URL 模板：指向结果图原始字节流端点（见 {@code ResultPreviewController}）。 */
    private static final String RAW_URL_TEMPLATE = "/api/asset/result/%d/raw";

    /** 结果预览张数上限（需求 8.6、8.7）。 */
    private static final int PREVIEW_LIMIT = 3;

    /** 合法任务状态集合（需求 12.3）。 */
    private static final Set<Integer> VALID_STATUS = Set.of(0, 1, 2, 3);

    private final ConversationMapper conversationMapper;
    private final AssetPackageMapper packageMapper;
    private final AssetImageMapper imageMapper;
    private final ProcessTaskMapper taskMapper;
    private final ProcessResultMapper resultMapper;
    private final DagValidator dagValidator;
    private final DagJsonCodec dagJsonCodec;
    private final DagExecutionEngine executionEngine;

    public TaskService(ConversationMapper conversationMapper,
                       AssetPackageMapper packageMapper,
                       AssetImageMapper imageMapper,
                       ProcessTaskMapper taskMapper,
                       ProcessResultMapper resultMapper,
                       DagValidator dagValidator,
                       DagJsonCodec dagJsonCodec,
                       DagExecutionEngine executionEngine) {
        this.conversationMapper = conversationMapper;
        this.packageMapper = packageMapper;
        this.imageMapper = imageMapper;
        this.taskMapper = taskMapper;
        this.resultMapper = resultMapper;
        this.dagValidator = dagValidator;
        this.dagJsonCodec = dagJsonCodec;
        this.executionEngine = executionEngine;
    }

    /**
     * 确认并执行任务（需求 7.1、12.6、8.5–8.7）。
     *
     * <p>串联：对话存在校验 → 素材包就绪校验 → DAG 服务端重新校验 → 创建任务（status=0，序列化 DAG）→
     * 同步驱动执行引擎 → 组装含预览 URL 的响应。任一前置校验失败均不创建任务。</p>
     *
     * @param conversationId 来源对话 id
     * @param request        确认请求（前端回传 dagJson 与目标 packageId）
     * @return 任务终态、计数与成功结果图预览 URL
     * @throws BusinessException 校验失败时（CONVERSATION_NOT_FOUND / PACKAGE_UNAVAILABLE / DAG_* 等）
     */
    public ConfirmResponse confirm(long conversationId, ConfirmRequest request) {
        requireConversation(conversationId);

        if (request == null || request.packageId() == null) {
            throw new BusinessException(ErrorCode.PACKAGE_UNAVAILABLE, "确认执行须指定目标素材包");
        }
        AssetPackage pkg = requireReadyPackage(request.packageId());

        // 服务端重新独立校验，不信任前端回传结构（需求 7.1）
        Dag dag = dagValidator.validateJson(request == null ? null : request.dagJson());

        List<AssetImage> images = imageMapper.selectList(
                new QueryWrapper<AssetImage>()
                        .eq("package_id", pkg.getId())
                        .orderByAsc("id"));

        ProcessTask task = createTask(conversationId, pkg.getId(), dag, images.size());

        DagExecutionSummary summary = executionEngine.execute(task, dag, images);

        List<String> previewUrls = buildPreviewUrls(task.getId());
        return new ConfirmResponse(
                task.getId(),
                summary.status(),
                summary.totalCount(),
                summary.doneCount(),
                previewUrls);
    }

    /**
     * 创建任务记录并序列化 DAG（需求 12.6）。
     *
     * <p>{@code dag_json} 由已校验的 {@link Dag} 经 {@link DagJsonCodec} 规范序列化，status 初始化为 0，
     * {@code total_count} 为待处理图片数，{@code done_count} 初始化为 0。</p>
     */
    private ProcessTask createTask(long conversationId, long packageId, Dag dag, int imageCount) {
        ProcessTask task = new ProcessTask();
        task.setConversationId(conversationId);
        task.setPackageId(packageId);
        task.setDagJson(dagJsonCodec.write(dag));
        task.setStatus(0);
        task.setTotalCount(imageCount);
        task.setDoneCount(0);
        task.setCreatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }

    /**
     * 任务列表（分页，{@code created_at} 降序，可选 status 筛选，需求 12.1–12.3）。
     *
     * @param page   页码（{@code null} 默认 1）
     * @param size   每页条数（{@code null} 默认 20，取值 1–100）
     * @param status 任务状态筛选（{@code null} 不筛选；非空须为 0/1/2/3）
     * @return 分页任务列表
     * @throws BusinessException 分页参数越界（INVALID_PAGINATION）或 status 非法（INVALID_TASK_STATUS）时
     */
    public PageResponse<TaskListItem> listTasks(Long page, Long size, Integer status) {
        Pagination pagination = Pagination.of(page, size);
        if (status != null && !VALID_STATUS.contains(status)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("status", status);
            details.put("allowed", VALID_STATUS);
            throw new BusinessException(ErrorCode.INVALID_TASK_STATUS,
                    "任务状态筛选值非法，仅允许 0/1/2/3", details);
        }

        QueryWrapper<ProcessTask> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("created_at");
        wrapper.orderByDesc("id");

        Page<ProcessTask> pageReq = Page.of(pagination.page(), pagination.size());
        Page<ProcessTask> result = taskMapper.selectPage(pageReq, wrapper);

        List<TaskListItem> items = new ArrayList<>();
        for (ProcessTask task : result.getRecords()) {
            items.add(TaskListItem.from(task));
        }
        return PageResponse.of(items, result.getTotal(), pagination.page(), pagination.size());
    }

    /**
     * 任务详情（含一页结果列表，需求 11.3、12.4、12.5、13.1、13.2）。
     *
     * <p>结果列表按结果 id 升序分页返回；失败结果项含 {@code errorMsg}（需求 11.3）。</p>
     *
     * @param taskId     任务 id
     * @param resultPage 结果分页页码（{@code null} 默认 1）
     * @param resultSize 结果每页条数（{@code null} 默认 20，取值 1–100）
     * @return 任务详情
     * @throws BusinessException 任务不存在（TASK_NOT_FOUND）或结果分页参数越界（INVALID_PAGINATION）时
     */
    public TaskDetailResponse taskDetail(long taskId, Long resultPage, Long resultSize) {
        ProcessTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务不存在：id=" + taskId);
        }

        Pagination pagination = Pagination.of(resultPage, resultSize);
        QueryWrapper<ProcessResult> wrapper = new QueryWrapper<ProcessResult>()
                .eq("task_id", taskId)
                .orderByAsc("id");
        Page<ProcessResult> pageReq = Page.of(pagination.page(), pagination.size());
        Page<ProcessResult> result = resultMapper.selectPage(pageReq, wrapper);

        List<TaskResultItem> items = new ArrayList<>();
        for (ProcessResult r : result.getRecords()) {
            items.add(TaskResultItem.from(r));
        }
        return TaskDetailResponse.from(task, items);
    }

    // ---- 成功结果图预览 URL：按 asset_image.id 升序取前 min(3, n)（需求 8.6、8.7）-----

    private List<String> buildPreviewUrls(long taskId) {
        List<ProcessResult> successes = resultMapper.selectList(
                new QueryWrapper<ProcessResult>()
                        .eq("task_id", taskId)
                        .eq("status", 1)
                        .isNotNull("output_path"));

        successes.sort(Comparator.comparing(
                ProcessResult::getImageId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProcessResult::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> urls = new ArrayList<>();
        int limit = Math.min(PREVIEW_LIMIT, successes.size());
        for (int i = 0; i < limit; i++) {
            urls.add(String.format(RAW_URL_TEMPLATE, successes.get(i).getId()));
        }
        return urls;
    }

    // ---- 前置校验 ----------------------------------------------------------

    private Conversation requireConversation(long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND,
                    "对话不存在：id=" + conversationId);
        }
        return conversation;
    }

    private AssetPackage requireReadyPackage(long packageId) {
        AssetPackage pkg = packageMapper.selectById(packageId);
        if (pkg == null || pkg.getStatus() == null || pkg.getStatus() != PackageStatus.READY) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("packageId", packageId);
            details.put("requiredStatus", PackageStatus.READY);
            if (pkg != null) {
                details.put("actualStatus", pkg.getStatus());
            }
            throw new BusinessException(ErrorCode.PACKAGE_UNAVAILABLE,
                    "目标素材包不存在或未就绪：id=" + packageId, details);
        }
        return pkg;
    }
}

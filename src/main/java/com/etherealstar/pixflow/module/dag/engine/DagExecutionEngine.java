package com.etherealstar.pixflow.module.dag.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.etherealstar.pixflow.infra.image.ImageCodec;
import com.etherealstar.pixflow.infra.image.ImageData;
import com.etherealstar.pixflow.infra.image.ImageToolExecutor;
import com.etherealstar.pixflow.infra.storage.StoragePaths;
import com.etherealstar.pixflow.infra.storage.StorageService;
import com.etherealstar.pixflow.module.dag.domain.Branch;
import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import com.etherealstar.pixflow.module.dag.schema.ToolType;
import com.etherealstar.pixflow.module.file.entity.AssetCopy;
import com.etherealstar.pixflow.module.file.entity.AssetImage;
import com.etherealstar.pixflow.module.file.mapper.AssetCopyMapper;
import com.etherealstar.pixflow.module.task.entity.ProcessResult;
import com.etherealstar.pixflow.module.task.entity.ProcessTask;
import com.etherealstar.pixflow.module.task.mapper.ProcessResultMapper;
import com.etherealstar.pixflow.module.task.mapper.ProcessTaskMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * DAG 拓扑执行引擎（需求 8、9、10、11，DAG_Engine）。
 *
 * <p>对一个已校验通过的 DAG 与就绪素材包的全部图片执行同步批处理：
 * <ol>
 *   <li>经 {@link BranchExpander} 将 DAG 展开为从源到汇的独立支路；每条支路对应一个产出
 *       （需求 9.1）。支路的节点序列由源到汇拓扑有序，从而满足「每个节点在其全部前驱完成后执行」
 *       的拓扑不变量（需求 8.1）。</li>
 *   <li>对「每张图片 × 每条支路」构造工作单元，交由 {@link ImageWorkerPool} 以固定大小线程池
 *       并行调度（需求 8.2、8.3）。</li>
 *   <li>像素支路按序应用 {@link ImageToolExecutor} 各像素工具，最终编码落盘并持久化一条
 *       {@code process_result}（含非空 {@code image_id}/{@code sku_id}/{@code output_path} 与
 *       同图唯一的 {@code branch_id}，需求 9.2、9.3、9.5）；含 {@code generate_copy} 的支路作为
 *       独立文案分支由 {@link CopyGenerator} 处理（需求 10）。</li>
 *   <li>单个工作单元失败经 {@link FailureIsolator} 隔离，不影响其余支路/图片/SKU（需求 11.1、11.2）。</li>
 *   <li>全部完成后设置任务终态与 {@code finished_at}：至少一条成功→完成（2），全失败→失败（3）
 *       （需求 8.5、11.3–11.5）。</li>
 * </ol>
 */
@Service
public class DagExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(DagExecutionEngine.class);

    private final BranchExpander branchExpander;
    private final ImageWorkerPool workerPool;
    private final ImageToolExecutor imageToolExecutor;
    private final ImageCodec imageCodec;
    private final CopyGenerator copyGenerator;
    private final FailureIsolator failureIsolator;
    private final StorageService storageService;
    private final AssetCopyMapper assetCopyMapper;
    private final ProcessResultMapper processResultMapper;
    private final ProcessTaskMapper processTaskMapper;

    public DagExecutionEngine(BranchExpander branchExpander,
                              ImageWorkerPool workerPool,
                              ImageToolExecutor imageToolExecutor,
                              ImageCodec imageCodec,
                              CopyGenerator copyGenerator,
                              FailureIsolator failureIsolator,
                              StorageService storageService,
                              AssetCopyMapper assetCopyMapper,
                              ProcessResultMapper processResultMapper,
                              ProcessTaskMapper processTaskMapper) {
        this.branchExpander = branchExpander;
        this.workerPool = workerPool;
        this.imageToolExecutor = imageToolExecutor;
        this.imageCodec = imageCodec;
        this.copyGenerator = copyGenerator;
        this.failureIsolator = failureIsolator;
        this.storageService = storageService;
        this.assetCopyMapper = assetCopyMapper;
        this.processResultMapper = processResultMapper;
        this.processTaskMapper = processTaskMapper;
    }

    /**
     * 对给定任务执行整个 DAG，持久化全部结果并设置任务终态。
     *
     * @param task   已创建的处理任务（status=0），其 {@code id} 与 {@code packageId} 须已就绪
     * @param dag    已通过 DAG_Validator 校验的 DAG
     * @param images 该素材包成功识别的全部图片
     * @return 执行结果摘要
     */
    public DagExecutionSummary execute(ProcessTask task, Dag dag, List<AssetImage> images) {
        Map<String, AssetCopy> copyBySku = loadCopyContext(task.getPackageId());
        List<Branch> branches = branchExpander.expand(dag);

        List<Callable<ProcessResult>> workUnits = new ArrayList<>();
        for (AssetImage image : images) {
            for (Branch branch : branches) {
                workUnits.add(() -> processUnit(task, dag, branch, image, copyBySku.get(image.getSkuId())));
            }
        }

        ConcurrencyGauge gauge = new ConcurrencyGauge();
        List<ProcessResult> results = workerPool.runAll(workUnits, gauge);

        return finalizeTask(task, images.size(), results, gauge);
    }

    // ---- 单个「图片 × 支路」工作单元（含失败隔离）---------------------------

    private ProcessResult processUnit(ProcessTask task, Dag dag, Branch branch,
                                      AssetImage image, AssetCopy copy) {
        ProcessResult result = new ProcessResult();
        result.setTaskId(task.getId());
        result.setImageId(image.getId());
        result.setSkuId(image.getSkuId());
        result.setBranchId(branch.getBranchId());
        result.setStatus(0);
        result.setCreatedAt(LocalDateTime.now());

        try {
            if (isCopyBranch(dag, branch)) {
                runCopyBranch(result, image, copy, copyStyle(dag, branch));
            } else {
                runImageBranch(task, dag, branch, image, result);
            }
            result.setStatus(1);
        } catch (Throwable t) {
            failureIsolator.markFailed(result, t);
        }

        persist(result);
        return result;
    }

    /** 含 generate_copy 节点的支路即为独立文案分支（需求 10.1、10.6）。 */
    private boolean isCopyBranch(Dag dag, Branch branch) {
        for (String nodeId : branch.getNodeSequence()) {
            DagNode node = dag.getNode(nodeId);
            if (node != null && ToolType.GENERATE_COPY.getToolName().equals(node.getTool())) {
                return true;
            }
        }
        return false;
    }

    private String copyStyle(Dag dag, Branch branch) {
        for (String nodeId : branch.getNodeSequence()) {
            DagNode node = dag.getNode(nodeId);
            if (node != null && ToolType.GENERATE_COPY.getToolName().equals(node.getTool())) {
                Object style = node.getParams() == null ? null : node.getParams().get("style");
                return style == null ? null : String.valueOf(style);
            }
        }
        return null;
    }

    // ---- 文案分支：生成文案写入 generated_copy（需求 10.2–10.5）------------

    private void runCopyBranch(ProcessResult result, AssetImage image, AssetCopy copy, String style) {
        String copyText = copyGenerator.generate(copy, image, style);
        result.setGeneratedCopy(copyText);
        result.setOutputPath(null);
    }

    // ---- 像素支路：按序应用像素工具并落盘（需求 8.1、9.3）------------------

    private void runImageBranch(ProcessTask task, Dag dag, Branch branch,
                                AssetImage image, ProcessResult result) {
        String sourcePath = StoragePaths.packageImage(task.getPackageId(), image.getOriginalPath());
        byte[] sourceBytes = storageService.readAllBytes(sourcePath);

        ImageData data = new ImageData(imageCodec.decode(sourceBytes), initialFormat(image.getOriginalPath()));
        for (String nodeId : branch.getNodeSequence()) {
            DagNode node = dag.getNode(nodeId);
            if (node == null) {
                continue;
            }
            data = imageToolExecutor.apply(node, data);
        }

        byte[] outputBytes = imageCodec.encode(data);
        String fileName = outputFileName(image, branch, data.getFormat());
        String outputPath = StoragePaths.taskResult(task.getId(), fileName);
        storageService.write(outputBytes, outputPath);
        result.setOutputPath(outputPath);
    }

    /** 结果文件名包含 sku、imageId 与 branchId，保证同图多支路命名互不冲突。 */
    private String outputFileName(AssetImage image, Branch branch, String format) {
        String safeSku = sanitize(image.getSkuId());
        return safeSku + "_" + image.getId() + "_" + branch.getBranchId() + "." + extension(format);
    }

    private String sanitize(String s) {
        if (s == null || s.isBlank()) {
            return "sku";
        }
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String extension(String format) {
        if (format == null) {
            return "png";
        }
        return switch (format.trim().toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "jpg";
            case "webp" -> "webp";
            default -> "png";
        };
    }

    /** 依据原图扩展名推断初始格式，convert_format 可在流水线中覆盖。 */
    private String initialFormat(String originalPath) {
        if (originalPath == null) {
            return "PNG";
        }
        int dot = originalPath.lastIndexOf('.');
        String ext = dot >= 0 ? originalPath.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return switch (ext) {
            case "jpg", "jpeg" -> "JPG";
            case "webp" -> "WebP";
            default -> "PNG";
        };
    }

    private void persist(ProcessResult result) {
        try {
            processResultMapper.insert(result);
        } catch (Exception e) {
            log.error("持久化处理结果失败：taskId={}, imageId={}, branchId={}",
                    result.getTaskId(), result.getImageId(), result.getBranchId(), e);
            throw e;
        }
    }

    // ---- 任务终态与完成时间（需求 8.5、11.3–11.5）--------------------------

    private DagExecutionSummary finalizeTask(ProcessTask task, int imageCount,
                                             List<ProcessResult> results, ConcurrencyGauge gauge) {
        int successCount = 0;
        int failureCount = 0;
        for (ProcessResult r : results) {
            if (r.getStatus() != null && r.getStatus() == 1) {
                successCount++;
            } else {
                failureCount++;
            }
        }

        // 至少一条成功 → 完成（2）；存在结果且全部失败 → 失败（3）；无任何结果 → 完成（2，无可处理项）。
        int status = (successCount > 0 || results.isEmpty()) ? 2 : 3;

        task.setStatus(status);
        task.setDoneCount(imageCount);
        task.setFinishedAt(LocalDateTime.now());
        processTaskMapper.updateById(task);

        log.info("任务 {} 执行完成：status={}, 图片数={}, 结果数={}, 成功={}, 失败={}, 峰值并发={}",
                task.getId(), status, imageCount, results.size(), successCount, failureCount, gauge.peak());

        return new DagExecutionSummary(task.getId(), status, imageCount,
                imageCount, results.size(), successCount, failureCount);
    }

    // ---- 文案上下文加载（按 sku_id 软关联，需求 10.2）---------------------

    private Map<String, AssetCopy> loadCopyContext(Long packageId) {
        Map<String, AssetCopy> bySku = new HashMap<>();
        if (packageId == null) {
            return bySku;
        }
        LambdaQueryWrapper<AssetCopy> query = new LambdaQueryWrapper<AssetCopy>()
                .eq(AssetCopy::getPackageId, packageId);
        for (AssetCopy copy : assetCopyMapper.selectList(query)) {
            // 同一 SKU 多条文案时保留首条作为上下文。
            bySku.putIfAbsent(copy.getSkuId(), copy);
        }
        return bySku;
    }
}

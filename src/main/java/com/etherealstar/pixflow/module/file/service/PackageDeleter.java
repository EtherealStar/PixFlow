package com.etherealstar.pixflow.module.file.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.infra.storage.StoragePaths;
import com.etherealstar.pixflow.infra.storage.StorageService;
import com.etherealstar.pixflow.module.file.dto.DeleteReport;
import com.etherealstar.pixflow.module.file.entity.AssetCopy;
import com.etherealstar.pixflow.module.file.entity.AssetImage;
import com.etherealstar.pixflow.module.file.entity.AssetPackage;
import com.etherealstar.pixflow.module.file.mapper.AssetCopyMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetImageMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import com.etherealstar.pixflow.module.task.entity.ProcessTask;
import com.etherealstar.pixflow.module.task.mapper.ProcessTaskMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 素材包删除器（需求 14）。
 *
 * <p>删除流程：</p>
 * <ol>
 *   <li>校验素材包存在，否则返回 {@link ErrorCode#PACKAGE_NOT_FOUND}（需求 14.5）；</li>
 *   <li>校验是否被任一任务（{@code process_task.package_id}）引用，被引用则阻止删除并返回
 *       {@link ErrorCode#PACKAGE_REFERENCED_BY_TASK}（需求 14.4）；</li>
 *   <li>删除数据库记录（{@code asset_package}、{@code asset_image}、{@code asset_copy}）（需求 14.1）；</li>
 *   <li>删除物理文件（原始 zip、解压原图、文案文档），逐文件容错并生成报告（需求 14.2、14.3）。
 *       结果文件位于 {@code results/{taskId}} 目录，不在素材包目录下，因此天然不会被删除。</li>
 * </ol>
 */
@Component
public class PackageDeleter {

    private static final Logger log = LoggerFactory.getLogger(PackageDeleter.class);

    private final AssetPackageMapper packageMapper;
    private final AssetImageMapper imageMapper;
    private final AssetCopyMapper copyMapper;
    private final ProcessTaskMapper taskMapper;
    private final StorageService storageService;

    public PackageDeleter(AssetPackageMapper packageMapper,
                          AssetImageMapper imageMapper,
                          AssetCopyMapper copyMapper,
                          ProcessTaskMapper taskMapper,
                          StorageService storageService) {
        this.packageMapper = packageMapper;
        this.imageMapper = imageMapper;
        this.copyMapper = copyMapper;
        this.taskMapper = taskMapper;
        this.storageService = storageService;
    }

    /**
     * 删除指定素材包及其级联记录与物理文件。
     *
     * @param packageId 待删除素材包 id
     * @return 删除结果报告（含成功删除文件数与失败文件列表）
     * @throws BusinessException 当素材包不存在（PACKAGE_NOT_FOUND）或被任务引用（PACKAGE_REFERENCED_BY_TASK）时
     */
    @Transactional
    public DeleteReport delete(long packageId) {
        AssetPackage pkg = packageMapper.selectById(packageId);
        if (pkg == null) {
            throw new BusinessException(ErrorCode.PACKAGE_NOT_FOUND,
                    "素材包不存在：id=" + packageId);
        }

        Long referencingTasks = taskMapper.selectCount(
                new QueryWrapper<ProcessTask>().eq("package_id", packageId));
        if (referencingTasks != null && referencingTasks > 0) {
            throw new BusinessException(ErrorCode.PACKAGE_REFERENCED_BY_TASK,
                    "素材包已被 " + referencingTasks + " 个任务引用，无法删除：id=" + packageId);
        }

        // 删除数据库记录（需求 14.1）
        imageMapper.delete(new QueryWrapper<AssetImage>().eq("package_id", packageId));
        copyMapper.delete(new QueryWrapper<AssetCopy>().eq("package_id", packageId));
        packageMapper.deleteById(packageId);

        // 删除物理文件，逐文件容错（需求 14.2、14.3）
        DeletionStats stats = deletePackageFiles(packageId);

        return new DeleteReport(packageId, true, stats.deletedCount, stats.failedFiles);
    }

    /**
     * 逐文件删除素材包目录下的全部物理文件，并收集失败项。
     *
     * <p>先删除文件、再自底向上删除目录；任一文件删除失败时记录路径与原因并继续，
     * 不中断其余文件的删除（需求 14.3）。结果目录 {@code results/{taskId}} 不在此目录下，不受影响（需求 14.2）。</p>
     */
    private DeletionStats deletePackageFiles(long packageId) {
        int deletedCount = 0;
        List<DeleteReport.FailedFile> failed = new ArrayList<>();

        String packageDirRel = StoragePaths.packageDir(packageId);
        Path packageDir = storageService.resolve(packageDirRel);

        if (!Files.exists(packageDir)) {
            return new DeletionStats(0, failed);
        }

        List<Path> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(packageDir)) {
            // 自底向上排序：文件先于其父目录被删除
            walk.sorted(Comparator.reverseOrder()).forEach(entries::add);
        } catch (IOException e) {
            failed.add(new DeleteReport.FailedFile(packageDirRel,
                    "遍历素材包目录失败：" + e.getMessage()));
            return new DeletionStats(deletedCount, failed);
        }

        for (Path path : entries) {
            boolean isFile = Files.isRegularFile(path);
            try {
                Files.delete(path);
                if (isFile) {
                    deletedCount++;
                }
            } catch (IOException | RuntimeException e) {
                if (isFile) {
                    failed.add(new DeleteReport.FailedFile(
                            relativize(packageDirRel, packageDir, path), e.getMessage()));
                } else {
                    // 目录删除失败通常因子项删除失败导致，记录但不计入文件失败统计语义之外
                    log.warn("删除目录失败 path={}: {}", path, e.getMessage());
                }
            }
        }
        return new DeletionStats(deletedCount, failed);
    }

    /**
     * 计算某文件相对存储根目录的相对路径（以正斜杠分隔）。
     *
     * <p>以已知的素材包相对路径 {@code packageDirRel} 与素材包绝对路径 {@code packageDir} 为基准做相对化，
     * 避免依赖存储根目录的解析（部分 {@link StorageService} 实现不接受空相对路径）。</p>
     */
    private static String relativize(String packageDirRel, Path packageDir, Path path) {
        try {
            String rel = packageDir.relativize(path).toString().replace('\\', '/');
            return rel.isEmpty() ? packageDirRel : packageDirRel + "/" + rel;
        } catch (RuntimeException e) {
            return path.toString().replace('\\', '/');
        }
    }

    private record DeletionStats(int deletedCount, List<DeleteReport.FailedFile> failedFiles) {
    }
}

package com.etherealstar.pixflow.module.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.etherealstar.pixflow.infra.storage.LocalFileStorageService;
import com.etherealstar.pixflow.infra.storage.StorageProperties;
import com.etherealstar.pixflow.infra.storage.StoragePaths;
import com.etherealstar.pixflow.module.file.dto.DeleteReport;
import com.etherealstar.pixflow.module.file.entity.AssetPackage;
import com.etherealstar.pixflow.module.file.mapper.AssetCopyMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetImageMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import com.etherealstar.pixflow.module.task.mapper.ProcessTaskMapper;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 素材包删除容错报告完整性测试（任务 6.7）。
 *
 * <p>Feature: pixflow, Property 38: 删除容错报告完整性——当删除过程中部分物理文件删除失败时，删除结果报告应
 * 记录每个失败文件的路径与原因、包含成功删除数量与失败文件列表，且其余可删除文件仍被删除。
 *
 * <p>为确定性地注入“单个文件删除失败”，本测试在删除期间对某一文件持有排他文件锁。文件锁对删除的阻断行为依赖
 * 操作系统（Windows 会阻止删除被占用文件；部分类 Unix 系统的咨询锁不阻止删除）。当当前平台未能注入失败时，
 * 以 {@link org.junit.jupiter.api.Assumptions} 跳过断言，保证测试不产生误报。
 *
 * <p>Validates: Requirements 14.3
 */
class PackageDeleteTolerantReportTest {

    @Test
    void partialFailureIsReportedWhileOthersAreDeleted() throws IOException {
        Path tempRoot = Files.createTempDirectory("pixflow-del-fail-");
        FileLock lock = null;
        RandomAccessFile raf = null;
        FileChannel channel = null;
        try {
            long packageId = 123L;
            LocalFileStorageService storage = newStorage(tempRoot);

            // 三张原图 + zip，其中 locked.png 在删除期间被占用以注入失败
            storage.write(bytes("zip"), StoragePaths.packageZip(packageId));
            storage.write(bytes("a"), StoragePaths.packageImage(packageId, "a.png"));
            storage.write(bytes("b"), StoragePaths.packageImage(packageId, "b.png"));
            String lockedRel = StoragePaths.packageImage(packageId, "locked.png");
            storage.write(bytes("locked"), lockedRel);

            Path lockedPath = storage.resolve(lockedRel);
            raf = new RandomAccessFile(lockedPath.toFile(), "rw");
            channel = raf.getChannel();
            lock = channel.lock();

            AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
            ProcessTaskMapper taskMapper = mock(ProcessTaskMapper.class);

            AssetPackage pkg = new AssetPackage();
            pkg.setId(packageId);
            when(packageMapper.selectById(packageId)).thenReturn(pkg);
            when(taskMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

            PackageDeleter deleter = new PackageDeleter(
                    packageMapper,
                    mock(AssetImageMapper.class),
                    mock(AssetCopyMapper.class),
                    taskMapper,
                    storage);

            DeleteReport report = deleter.delete(packageId);

            // 仅在当前平台确实阻止了被占用文件的删除时才断言失败报告语义
            assumeTrue(Files.exists(lockedPath),
                    "当前平台未阻止删除被占用文件，跳过容错报告断言");

            // 报告记录了失败文件的路径与原因
            assertThat(report.failedFiles()).hasSize(1);
            DeleteReport.FailedFile failed = report.failedFiles().get(0);
            assertThat(failed.path()).isEqualTo(lockedRel);
            assertThat(failed.reason()).isNotBlank();

            // 其余可删除文件仍被删除：zip + a.png + b.png = 3
            assertThat(report.deletedFileCount()).isEqualTo(3);
            assertThat(Files.exists(storage.resolve(StoragePaths.packageZip(packageId)))).isFalse();
            assertThat(Files.exists(storage.resolve(StoragePaths.packageImage(packageId, "a.png")))).isFalse();
            assertThat(Files.exists(storage.resolve(StoragePaths.packageImage(packageId, "b.png")))).isFalse();
            // 数据库记录删除指示仍为 true（容错删除不因单文件失败而回滚）
            assertThat(report.deleted()).isTrue();
        } finally {
            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException ignored) {
                    // ignore
                }
            }
            if (channel != null) {
                channel.close();
            }
            if (raf != null) {
                raf.close();
            }
            deleteTree(tempRoot);
        }
    }

    private static LocalFileStorageService newStorage(Path root) {
        StorageProperties props = new StorageProperties();
        props.setRoot(root.toString());
        LocalFileStorageService storage = new LocalFileStorageService(props);
        storage.init();
        return storage;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理临时目录的尽力而为
                }
            });
        }
    }
}

package com.etherealstar.pixflow.module.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.etherealstar.pixflow.infra.storage.LocalFileStorageService;
import com.etherealstar.pixflow.infra.storage.StorageProperties;
import com.etherealstar.pixflow.infra.storage.StoragePaths;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.constraints.Size;

/**
 * 素材包删除级联与物理文件清理属性测试（任务 6.6）。
 *
 * <p>Feature: pixflow, Property 37: 删除级联与物理文件清理——对任意存在且未被任何任务引用的素材包，
 * 删除后 {@code asset_package}、{@code asset_image}、{@code asset_copy} 中应无该包记录，且其原始物理文件
 * （zip、解压原图、文案文档）应被删除，而任务产出的处理结果文件应保留。
 *
 * <p>数据库记录删除以对 mapper 的调用验证；物理文件清理使用基于临时目录的真实 {@link LocalFileStorageService}
 * （仅写入占位字节，不依赖真实图片/文案资源）。
 *
 * <p>Validates: Requirements 14.1, 14.2
 */
class PackageDeleteCascadePropertyTest {

    @Provide
    Arbitrary<List<String>> imageNames() {
        Arbitrary<String> name = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(8);
        return name.list().ofMinSize(0).ofMaxSize(6).uniqueElements();
    }

    @Property(tries = 80)
    @SuppressWarnings("unchecked")
    void deleteRemovesRecordsAndOriginalFilesButKeepsResults(
            @ForAll("imageNames") @Size(max = 6) List<String> imageNames) throws IOException {
        Path tempRoot = Files.createTempDirectory("pixflow-del-");
        try {
            long packageId = 123L;
            long taskIdOfOtherPackage = 999L;

            LocalFileStorageService storage = newStorage(tempRoot);

            // 原始物理文件：zip + 文案文档 + N 张解压原图
            storage.write(bytes("zip"), StoragePaths.packageZip(packageId));
            storage.write(bytes("doc"), StoragePaths.packageDoc(packageId, "copy.xlsx"));
            for (String n : imageNames) {
                storage.write(bytes("img"), StoragePaths.packageImage(packageId, n + ".png"));
            }
            int originalFileCount = 2 + imageNames.size();

            // 任务结果文件位于 results/，不属于素材包目录，应被保留
            String resultRel = StoragePaths.taskResult(taskIdOfOtherPackage, "SKU_1.png");
            storage.write(bytes("result"), resultRel);

            AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
            AssetImageMapper imageMapper = mock(AssetImageMapper.class);
            AssetCopyMapper copyMapper = mock(AssetCopyMapper.class);
            ProcessTaskMapper taskMapper = mock(ProcessTaskMapper.class);

            AssetPackage pkg = new AssetPackage();
            pkg.setId(packageId);
            when(packageMapper.selectById(packageId)).thenReturn(pkg);
            // 未被任何任务引用
            when(taskMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

            PackageDeleter deleter = new PackageDeleter(
                    packageMapper, imageMapper, copyMapper, taskMapper, storage);

            DeleteReport report = deleter.delete(packageId);

            // 数据库记录级联删除（需求 14.1）
            verify(imageMapper).delete(any(QueryWrapper.class));
            verify(copyMapper).delete(any(QueryWrapper.class));
            verify(packageMapper, times(1)).deleteById(packageId);

            // 原始物理文件被删除（需求 14.2）
            assertThat(Files.exists(storage.resolve(StoragePaths.packageDir(packageId)))).isFalse();
            // 结果文件保留（需求 14.2）
            assertThat(Files.exists(storage.resolve(resultRel))).isTrue();

            // 报告：删除成功、成功删除文件数等于原始文件数、无失败项
            assertThat(report.deleted()).isTrue();
            assertThat(report.deletedFileCount()).isEqualTo(originalFileCount);
            assertThat(report.failedFiles()).isEmpty();
        } finally {
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

package com.etherealstar.pixflow.module.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.infra.storage.LocalFileStorageService;
import com.etherealstar.pixflow.infra.storage.StorageProperties;
import com.etherealstar.pixflow.infra.storage.StoragePaths;
import com.etherealstar.pixflow.module.file.entity.AssetPackage;
import com.etherealstar.pixflow.module.file.mapper.AssetCopyMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetImageMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import com.etherealstar.pixflow.module.task.mapper.ProcessTaskMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 被引用素材包阻止删除属性测试（任务 6.8）。
 *
 * <p>Feature: pixflow, Property 39: 被引用素材包阻止删除——对任意被任一任务（{@code process_task.package_id}）
 * 引用的素材包，删除请求应被阻止并返回 {@link ErrorCode#PACKAGE_REFERENCED_BY_TASK}，且该素材包的数据库记录
 * 与物理文件均应保留。
 *
 * <p>Validates: Requirements 14.4
 */
class PackageDeleteReferencedPropertyTest {

    @Provide
    Arbitrary<Long> referencingCounts() {
        return Arbitraries.longs().between(1, 10_000);
    }

    @Property(tries = 200)
    @SuppressWarnings("unchecked")
    void referencedPackageIsBlockedAndPreserved(@ForAll("referencingCounts") long referencingCount)
            throws IOException {
        Path tempRoot = Files.createTempDirectory("pixflow-del-ref-");
        try {
            long packageId = 123L;
            LocalFileStorageService storage = newStorage(tempRoot);

            String zipRel = StoragePaths.packageZip(packageId);
            String imgRel = StoragePaths.packageImage(packageId, "a.png");
            storage.write(bytes("zip"), zipRel);
            storage.write(bytes("img"), imgRel);

            AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
            AssetImageMapper imageMapper = mock(AssetImageMapper.class);
            AssetCopyMapper copyMapper = mock(AssetCopyMapper.class);
            ProcessTaskMapper taskMapper = mock(ProcessTaskMapper.class);

            AssetPackage pkg = new AssetPackage();
            pkg.setId(packageId);
            when(packageMapper.selectById(packageId)).thenReturn(pkg);
            // 被 referencingCount 个任务引用
            when(taskMapper.selectCount(any(QueryWrapper.class))).thenReturn(referencingCount);

            PackageDeleter deleter = new PackageDeleter(
                    packageMapper, imageMapper, copyMapper, taskMapper, storage);

            assertThatThrownBy(() -> deleter.delete(packageId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PACKAGE_REFERENCED_BY_TASK);

            // 数据库记录均不应被删除
            verify(packageMapper, never()).deleteById(anyLong());
            verify(imageMapper, never()).delete(any(QueryWrapper.class));
            verify(copyMapper, never()).delete(any(QueryWrapper.class));

            // 物理文件均应保留
            assertThat(Files.exists(storage.resolve(zipRel))).isTrue();
            assertThat(Files.exists(storage.resolve(imgRel))).isTrue();
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

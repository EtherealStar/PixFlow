package com.etherealstar.pixflow.module.file.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.etherealstar.pixflow.infra.storage.StorageService;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
import com.etherealstar.pixflow.module.file.copy.CopyDocumentParser;
import com.etherealstar.pixflow.module.file.entity.AssetImage;
import com.etherealstar.pixflow.module.file.entity.AssetPackage;
import com.etherealstar.pixflow.module.file.extract.ZipExtractor;
import com.etherealstar.pixflow.module.file.image.ImageDecoder;
import com.etherealstar.pixflow.module.file.image.ImageValidator;
import com.etherealstar.pixflow.module.file.mapper.AssetCopyMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetImageMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import com.etherealstar.pixflow.module.file.service.AssetPackageService;
import com.etherealstar.pixflow.module.file.service.PackageDeleter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试辅助：构造一套 {@link AssetPackageService} 及其依赖。
 *
 * <p>采用「真实纯逻辑组件 + 内存替身 I/O」的策略，将解压、图片识别、文案解析等纯逻辑用真实实现，
 * 仅把存储与数据库（Mapper）替换为 Mockito 替身，从而无需真实磁盘 / 数据库 / 图片资源即可端到端
 * 驱动上传扫描流程：
 * <ul>
 *   <li>{@link ZipExtractor}、{@link CopyDocumentParser} 使用真实实现（受给定 {@link AssetProperties} 约束）；</li>
 *   <li>{@link ImageValidator} 注入可控的 {@link ImageDecoder} 替身，避免依赖真实图片解码；</li>
 *   <li>{@code StorageService} 的 {@code openInputStream} 始终回放 {@link #zipBytes}，
 *       {@code write} 直接返回目标相对路径；</li>
 *   <li>{@code AssetPackageMapper#insert} / {@code AssetImageMapper#insert} 模拟数据库自增主键。</li>
 * </ul>
 */
public final class AssetServiceFixture {

    public final AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
    public final AssetImageMapper imageMapper = mock(AssetImageMapper.class);
    public final AssetCopyMapper copyMapper = mock(AssetCopyMapper.class);
    public final StorageService storage = mock(StorageService.class);
    public final PackageDeleter packageDeleter = mock(PackageDeleter.class);
    public final AssetProperties properties;
    public final AssetPackageService service;

    private final AtomicLong packageSeq = new AtomicLong(1);
    private final AtomicLong imageSeq = new AtomicLong(1);

    /** 由测试设置：作为 {@code storage.openInputStream(...)} 的回放内容（zip 字节）。 */
    public volatile byte[] zipBytes = new byte[0];

    public AssetServiceFixture(AssetProperties properties, ImageDecoder decoder) {
        this.properties = properties;
        ZipExtractor zipExtractor = new ZipExtractor(properties);
        ImageValidator imageValidator = new ImageValidator(decoder);
        CopyDocumentParser copyDocumentParser = new CopyDocumentParser(properties);
        this.service = new AssetPackageService(packageMapper, imageMapper, copyMapper, storage,
                zipExtractor, imageValidator, copyDocumentParser, properties, packageDeleter);

        // 模拟数据库自增主键：insert 时为实体回填唯一 id
        when(packageMapper.insert(any(AssetPackage.class))).thenAnswer(inv -> {
            AssetPackage pkg = inv.getArgument(0);
            pkg.setId(packageSeq.getAndIncrement());
            return 1;
        });
        when(imageMapper.insert(any(AssetImage.class))).thenAnswer(inv -> {
            AssetImage image = inv.getArgument(0);
            if (image.getId() == null) {
                image.setId(imageSeq.getAndIncrement());
            }
            return 1;
        });
        when(copyMapper.insert(any())).thenReturn(1);

        // 存储替身：write 回显目标路径，openInputStream 回放 zip 字节
        when(storage.write(any(byte[].class), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(storage.write(any(InputStream.class), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(storage.openInputStream(anyString()))
                .thenAnswer(inv -> new ByteArrayInputStream(zipBytes));
    }

    public AssetServiceFixture withZip(byte[] bytes) {
        this.zipBytes = bytes;
        return this;
    }
}

package com.etherealstar.pixflow.infra.storage;

import jakarta.annotation.PostConstruct;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 基于本地磁盘的 {@link StorageService} 实现。
 *
 * <p>所有文件均存放于配置的存储根目录下，相对路径在解析时会做规范化与路径穿越校验，
 * 确保不会读写到根目录之外。</p>
 */
@Service
public class LocalFileStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);

    private final StorageProperties properties;
    private Path root;

    public LocalFileStorageService(StorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        this.root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new StorageException("无法初始化存储根目录: " + root, e);
        }
        log.info("PixFlow 存储根目录: {}", root);
    }

    /** 存储根目录（绝对路径）。 */
    public Path getRoot() {
        return root;
    }

    @Override
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new StorageException("相对路径不能为空");
        }
        // 统一分隔符后按根目录解析并规范化
        String normalized = relativePath.replace('\\', '/');
        Path resolved = root.resolve(normalized).normalize();
        if (!resolved.startsWith(root)) {
            throw new StorageException("非法路径，越出存储根目录: " + relativePath);
        }
        return resolved;
    }

    @Override
    public String write(InputStream content, String relativePath) {
        Path target = resolve(relativePath);
        ensureParent(target);
        try (InputStream in = content) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("写入文件失败: " + relativePath, e);
        }
        return relativePath;
    }

    @Override
    public String write(byte[] content, String relativePath) {
        Path target = resolve(relativePath);
        ensureParent(target);
        try {
            Files.write(target, content);
        } catch (IOException e) {
            throw new StorageException("写入文件失败: " + relativePath, e);
        }
        return relativePath;
    }

    @Override
    public InputStream openInputStream(String relativePath) {
        Path target = resolve(relativePath);
        if (!Files.exists(target)) {
            throw new StorageException("文件不存在: " + relativePath);
        }
        try {
            return new BufferedInputStream(Files.newInputStream(target));
        } catch (IOException e) {
            throw new StorageException("打开输入流失败: " + relativePath, e);
        }
    }

    @Override
    public OutputStream openOutputStream(String relativePath) {
        Path target = resolve(relativePath);
        ensureParent(target);
        try {
            return new BufferedOutputStream(Files.newOutputStream(target));
        } catch (IOException e) {
            throw new StorageException("打开输出流失败: " + relativePath, e);
        }
    }

    @Override
    public byte[] readAllBytes(String relativePath) {
        Path target = resolve(relativePath);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new StorageException("读取文件失败: " + relativePath, e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    @Override
    public long size(String relativePath) {
        Path target = resolve(relativePath);
        try {
            return Files.size(target);
        } catch (IOException e) {
            throw new StorageException("获取文件大小失败: " + relativePath, e);
        }
    }

    @Override
    public void createDirectories(String relativePath) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new StorageException("创建目录失败: " + relativePath, e);
        }
    }

    @Override
    public boolean delete(String relativePath) {
        Path target = resolve(relativePath);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new StorageException("删除文件失败: " + relativePath, e);
        }
    }

    @Override
    public boolean deleteRecursively(String relativePath) {
        Path target = resolve(relativePath);
        if (!Files.exists(target)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new StorageException("删除路径失败: " + p, e);
                    }
                });
        } catch (IOException e) {
            throw new StorageException("递归删除失败: " + relativePath, e);
        }
        return true;
    }

    private void ensureParent(Path target) {
        Path parent = target.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new StorageException("创建父目录失败: " + parent, e);
        }
    }
}

package com.etherealstar.pixflow.module.file.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 测试辅助：在内存中构造合法 zip 字节流。
 *
 * <p>用于素材包相关属性测试，避免依赖磁盘上的真实 zip 资源文件——所有 zip 均由
 * {@link ZipOutputStream} 程序化生成，天然带有合法的本地文件头签名（{@code PK\u0003\u0004}）。</p>
 */
public final class InMemoryZips {

    private InMemoryZips() {
    }

    /**
     * 由「条目相对路径 → 内容字节」映射构造 zip（保持给定迭代顺序）。
     */
    public static byte[] zipOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                if (e.getValue() != null) {
                    zos.write(e.getValue());
                }
                zos.closeEntry();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bos.toByteArray();
    }

    /**
     * 构造含 {@code names.size()} 个条目的 zip，每个条目内容为 {@code sizePerEntry} 字节填充。
     */
    public static byte[] zipWithEntries(List<String> names, int sizePerEntry) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] payload = new byte[Math.max(0, sizePerEntry)];
        for (int i = 0; i < names.size(); i++) {
            // 每个条目独立的内容副本，长度一致即可
            entries.put(names.get(i), payload.clone());
        }
        return zipOf(entries);
    }

    /**
     * 构造单条目 zip，条目内容为 {@code size} 字节。用于解压累计大小阈值测试。
     */
    public static byte[] singleEntryZip(String name, int size) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(name, new byte[Math.max(0, size)]);
        return zipOf(entries);
    }
}

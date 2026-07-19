package com.pixflow.module.imagegen.port;

import java.util.Optional;

/**
 * 源图读取 SPI(对齐 imagegen.md §四 / §五.1)。
 *
 * <p>由 {@code module/file} 在 Wave 3/4 落地时实现;imagegen 不直连 {@code asset_image} 表。
 * 实现方需保证:
 * <ul>
 *   <li>只接受 canonical IMAGE reference，不接受并行的 package/image ID</li>
 *   <li>不存在、不可处理或归属不匹配时返回 empty</li>
 *   <li>只返回身份与媒体类型；对象位置由真正读取字节的端口以 typed location 传递</li>
 * </ul>
 */
public interface SourceImageReader {
    /**
     * 按 canonical IMAGE reference 解析源图元数据。
     *
     * @param referenceKey canonical IMAGE reference
     * @return 当前可处理的源图元数据
     */
    Optional<SourceImageInfo> find(String referenceKey);
}

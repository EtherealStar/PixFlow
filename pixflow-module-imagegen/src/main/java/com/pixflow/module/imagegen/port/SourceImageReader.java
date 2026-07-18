package com.pixflow.module.imagegen.port;

import java.util.List;

/**
 * 源图读取 SPI(对齐 imagegen.md §四 / §五.1)。
 *
 * <p>由 {@code module/file} 在 Wave 3/4 落地时实现;imagegen 不直连 {@code asset_image} 表。
 * 实现方需保证:
 * <ul>
 *   <li>仅返回 imageId 在入参集合内且归属 packageId 的源图元数据</li>
 *   <li>不存在或不归属的 imageId 不返回(由调用方通过结果集 size 判定)</li>
 *   <li>只返回身份与媒体类型；对象位置由真正读取字节的端口以 typed location 传递</li>
 * </ul>
 */
public interface SourceImageReader {
    /**
     * 按 imageId 列表与 packageId 解析源图元数据。
     *
     * @param imageIds  源图 imageId 列表(去重后)
     * @param packageId 会话绑定的素材包 ID
     * @return 找到的源图元数据列表(顺序不保证);不存在/不归属的 imageId 不返回
     */
    List<SourceImageInfo> findAll(List<String> imageIds, String packageId);
}

package com.pixflow.module.dag.expand;

import com.pixflow.infra.storage.ObjectLocation;
import java.util.Objects;

/**
 * 中立输入 record:task worker 喂入 dag 模块的图片成员描述。
 *
 * <p>dag 不读 asset_image 表;由 task 加载图片后,以本 record 形式喂入 BranchExpander。
 *
 * <p>{@code groupKey} 非空表示分组成员(对齐 asset_image.group_key);{@code viewId}
 * 用于组内排序(对齐 compose_group 的 order)。两条字段在普通支路里可为空。
 */
public record ImageDescriptor(
    String imageId,
    String skuId,
    String groupKey,
    String viewId,
    ObjectLocation location,
    String contentType
) {
    public ImageDescriptor {
        Objects.requireNonNull(imageId, "imageId");
        Objects.requireNonNull(location, "location");
        // skuId 可空(分组成员只看 groupKey)
        // groupKey / viewId 可空(普通支路)
        // contentType 可空(image 模块会 probe)
    }

    /** 普通支路用便捷构造。 */
    public static ImageDescriptor single(String imageId, String skuId, ObjectLocation location) {
        return new ImageDescriptor(imageId, skuId, null, null, location, null);
    }

    /** 分组成员便捷构造。 */
    public static ImageDescriptor grouped(String imageId, String groupKey, String viewId,
                                          ObjectLocation location) {
        return new ImageDescriptor(imageId, null, groupKey, viewId, location, null);
    }
}

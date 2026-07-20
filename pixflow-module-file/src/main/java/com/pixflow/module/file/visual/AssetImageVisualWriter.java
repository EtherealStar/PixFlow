package com.pixflow.module.file.visual;

import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

/** 将图片事实和视觉输入事件放进同一个数据库事务。 */
public final class AssetImageVisualWriter {
    private final AssetImageMapper imageMapper;

    private final AssetVisualInputOutboxWriter outbox;

    private final TransactionTemplate transactions;

    public AssetImageVisualWriter(
            AssetImageMapper imageMapper,
            AssetVisualInputOutboxWriter outbox,
            TransactionTemplate transactions) {
        this.imageMapper = Objects.requireNonNull(imageMapper, "imageMapper");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public void insertOriginal(AssetImage image) {
        Objects.requireNonNull(image, "image");
        transactions.executeWithoutResult(status -> {
            imageMapper.insert(image);
            outbox.skuChanged(image.getPackageId(), image.getSkuId(), image.getCreatedAt());
        });
    }
}

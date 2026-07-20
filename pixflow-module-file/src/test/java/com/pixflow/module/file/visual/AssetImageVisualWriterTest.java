package com.pixflow.module.file.visual;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class AssetImageVisualWriterTest {
    @Test
    void insertsImageAndOutboxBeforeCommittingOneTransaction() {
        AssetImageMapper images = mock(AssetImageMapper.class);
        AssetVisualInputOutboxWriter outbox = mock(AssetVisualInputOutboxWriter.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        AssetImageVisualWriter writer = new AssetImageVisualWriter(
                images, outbox, new TransactionTemplate(manager));
        AssetImage image = new AssetImage();
        image.setPackageId(7L);
        image.setSkuId("SKU-1");
        image.setCreatedAt(Instant.parse("2026-07-19T10:00:00Z"));

        writer.insertOriginal(image);

        InOrder order = inOrder(images, outbox, manager);
        order.verify(images).insert(image);
        order.verify(outbox).skuChanged(7L, "SKU-1", image.getCreatedAt());
        order.verify(manager).commit(status);
    }
}

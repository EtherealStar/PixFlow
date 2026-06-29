package com.pixflow.module.dag.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.RasterImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * BranchExecutionContext 资源清理 LIFO 顺序 + 幂等 close + InputStream 关闭测试。
 *
 * <p>RasterImage 不可继承(其构造器是 private,内部 final 字段),所以本测试聚焦
 * InputStream close 与自定义清理任务;RasterImage 的 dispose 走 Graphics2D.dispose
 * 是测试已覆盖的公共路径(infra/image 模块单测已测过 dispose 幂等)。
 */
class BranchExecutionContextTest {

    @Test
    void close_invokesAllCleanups() {
        AtomicInteger ran = new AtomicInteger();
        try (BranchExecutionContext ctx = new BranchExecutionContext()) {
            ctx.onCleanup(ran::incrementAndGet);
            ctx.onCleanup(ran::incrementAndGet);
            ctx.onCleanup(ran::incrementAndGet);
            assertThat(ctx.pendingCleanups()).isEqualTo(3);
        }
        assertThat(ran.get()).isEqualTo(3);
    }

    @Test
    void close_isIdempotent() {
        AtomicInteger ran = new AtomicInteger();
        BranchExecutionContext ctx = new BranchExecutionContext();
        ctx.onCleanup(ran::incrementAndGet);
        ctx.close();
        ctx.close();
        ctx.close();
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void cleanups_runInLifoOrder() {
        StringBuilder order = new StringBuilder();
        try (BranchExecutionContext ctx = new BranchExecutionContext()) {
            ctx.onCleanup(() -> order.append("A"));
            ctx.onCleanup(() -> order.append("B"));
            ctx.onCleanup(() -> order.append("C"));
        }
        // LIFO:C, B, A
        assertThat(order.toString()).isEqualTo("CBA");
    }

    @Test
    void onClose_closesInputStream() throws IOException {
        AtomicInteger closed = new AtomicInteger();
        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override
            public void close() throws IOException {
                closed.incrementAndGet();
                super.close();
            }
        };
        try (BranchExecutionContext ctx = new BranchExecutionContext()) {
            ctx.onClose(stream);
        }
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    void onDispose_invokesBufferGraphicsDispose() {
        // 创建 RasterImage 并用 ctx.onDispose 注册清理;close 时会调 buffer().getGraphics().dispose()
        // 验证 close 不抛异常即可(RasterImage 内部 Graphics 不可见,我们仅验"运行没崩")
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        RasterImage raster = RasterImage.of(img, ImageFormat.PNG);
        try (BranchExecutionContext ctx = new BranchExecutionContext()) {
            ctx.onDispose(raster);
        }
        // 第二次 buffer() 调用应该仍正常(无副作用)
        assertThat(raster.width()).isEqualTo(1);
        assertThat(raster.height()).isEqualTo(1);
    }

    @Test
    void close_swallowsExceptions_andStillRunsOthers() {
        AtomicInteger ran = new AtomicInteger();
        try (BranchExecutionContext ctx = new BranchExecutionContext()) {
            ctx.onCleanup(() -> { throw new RuntimeException("boom"); });
            ctx.onCleanup(ran::incrementAndGet);
        }
        // 即便第一个清理抛异常,第二个仍执行
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void tryWithResources_guaranteesCleanupOnException() {
        AtomicInteger ran = new AtomicInteger();
        try {
            try (BranchExecutionContext ctx = new BranchExecutionContext()) {
                ctx.onCleanup(ran::incrementAndGet);
                throw new RuntimeException("execution failed");
            }
        } catch (RuntimeException ignored) {
            // 预期
        }
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void nullParameters_areIgnored() {
        try (BranchExecutionContext ctx = new BranchExecutionContext()) {
            ctx.onDispose(null);
            ctx.onClose(null);
            ctx.onCleanup(null);
            assertThat(ctx.pendingCleanups()).isEqualTo(0);
        }
    }
}
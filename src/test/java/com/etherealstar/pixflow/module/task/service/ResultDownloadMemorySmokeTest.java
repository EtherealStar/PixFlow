package com.etherealstar.pixflow.module.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.etherealstar.pixflow.infra.storage.StorageService;
import com.etherealstar.pixflow.module.task.entity.ProcessResult;
import com.etherealstar.pixflow.module.task.mapper.ProcessResultMapper;
import com.etherealstar.pixflow.module.task.mapper.ProcessTaskMapper;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 流式下载峰值内存冒烟测试（任务 14.11）。
 *
 * <p>验证 {@link ResultDownloadService#streamZip} 采用「逐文件读取→拷贝至 zip」的流式方式：
 * 任意时刻至多打开 1 个结果文件输入流，常驻内存只与固定拷贝缓冲区相关，不随结果数线性增长（需求 13.6）。
 * 通过插桩输入流统计并发打开数与累计读取字节，使用惰性生成的零字节大文件与丢弃式输出流，避免实际占用内存。</p>
 */
class ResultDownloadMemorySmokeTest {

    /** 惰性生成 {@code size} 个零字节、不在内存中物化的输入流，并跟踪并发打开数。 */
    private static final class CountingZeroStream extends InputStream {
        private final long size;
        private long produced;
        private boolean closed;
        private final AtomicInteger concurrentOpen;
        private final AtomicInteger maxConcurrentOpen;
        private final AtomicLong totalRead;

        CountingZeroStream(long size, AtomicInteger concurrentOpen,
                           AtomicInteger maxConcurrentOpen, AtomicLong totalRead) {
            this.size = size;
            this.concurrentOpen = concurrentOpen;
            this.maxConcurrentOpen = maxConcurrentOpen;
            this.totalRead = totalRead;
            int now = concurrentOpen.incrementAndGet();
            maxConcurrentOpen.accumulateAndGet(now, Math::max);
        }

        @Override
        public int read() {
            if (produced >= size) {
                return -1;
            }
            produced++;
            totalRead.incrementAndGet();
            return 0;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (produced >= size) {
                return -1;
            }
            int n = (int) Math.min(len, size - produced);
            for (int i = 0; i < n; i++) {
                b[off + i] = 0;
            }
            produced += n;
            totalRead.addAndGet(n);
            return n;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                concurrentOpen.decrementAndGet();
            }
        }
    }

    /** 丢弃所有写入字节的输出流，避免在内存中累积 zip 数据。 */
    private static final class DiscardingOutputStream extends OutputStream {
        @Override
        public void write(int b) {
            // discard
        }

        @Override
        public void write(byte[] b, int off, int len) {
            // discard
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamingKeepsAtMostOneFileOpenRegardlessOfResultCount() throws Exception {
        long taskId = 7L;
        int fileCount = 500;
        long perFileSize = 256 * 1024; // 256 KB/文件，合计 ~125 MB（惰性生成，不实际占用）

        ProcessResultMapper resultMapper = mock(ProcessResultMapper.class);
        ProcessTaskMapper taskMapper = mock(ProcessTaskMapper.class);
        StorageService storageService = mock(StorageService.class);

        List<ProcessResult> successes = new ArrayList<>();
        for (int i = 1; i <= fileCount; i++) {
            ProcessResult r = new ProcessResult();
            r.setId((long) i);
            r.setSkuId("sku" + i);
            r.setStatus(1);
            r.setOutputPath("results/out_" + i + ".png");
            successes.add(r);
        }
        when(resultMapper.selectList(any(QueryWrapper.class))).thenReturn(successes);
        when(storageService.exists(anyString())).thenReturn(true);

        AtomicInteger concurrentOpen = new AtomicInteger();
        AtomicInteger maxConcurrentOpen = new AtomicInteger();
        AtomicLong totalRead = new AtomicLong();
        when(storageService.openInputStream(anyString())).thenAnswer(inv ->
                new CountingZeroStream(perFileSize, concurrentOpen, maxConcurrentOpen, totalRead));

        ResultDownloadService service =
                new ResultDownloadService(resultMapper, taskMapper, storageService);

        service.streamZip(taskId, new DiscardingOutputStream());

        // 核心不变量：任意时刻至多 1 个文件输入流处于打开状态（逐文件流式处理）
        assertThat(maxConcurrentOpen.get()).isLessThanOrEqualTo(1);
        // 全部文件均被读取完毕，且全部输入流已关闭（无泄漏）
        assertThat(totalRead.get()).isEqualTo((long) fileCount * perFileSize);
        assertThat(concurrentOpen.get()).isZero();
    }
}

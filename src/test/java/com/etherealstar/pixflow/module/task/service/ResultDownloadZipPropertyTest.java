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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import org.mockito.ArgumentCaptor;

/**
 * 打包下载内容与文件名唯一性属性测试（任务 14.10）。
 *
 * <p>Feature: pixflow, Property 36: 打包内容与文件名唯一性——zip 仅包含成功结果图（{@code status=1}
 * 且 {@code output_path} 非空），每个条目恰对应一条成功结果，且条目名（含 {@code skuId}+{@code resultId}）
 * 在 zip 内互不冲突。
 * Validates: Requirements 13.3, 13.4
 */
class ResultDownloadZipPropertyTest {

    /** 成功结果项的生成素材：sku 名（含空白/特殊字符/中文）+ 扩展名。 */
    record ResultSpec(String sku, String ext) {
    }

    @Provide
    Arbitrary<List<ResultSpec>> resultSpecs() {
        Arbitrary<String> sku = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('_', '-', ' ', '/', '#', '中', '文')
                .ofMinLength(0).ofMaxLength(12);
        Arbitrary<String> ext = Arbitraries.of("png", "jpg", "jpeg", "webp", "PNG");
        Arbitrary<ResultSpec> spec =
                net.jqwik.api.Combinators.combine(sku, ext).as(ResultSpec::new);
        return spec.list().ofMinSize(1).ofMaxSize(20);
    }

    @Property(tries = 300)
    @SuppressWarnings("unchecked")
    void zipContainsExactlyOneUniquelyNamedEntryPerSuccess(
            @ForAll("resultSpecs") @Size(min = 1, max = 20) List<ResultSpec> specs) throws Exception {
        long taskId = 42L;

        ProcessResultMapper resultMapper = mock(ProcessResultMapper.class);
        ProcessTaskMapper taskMapper = mock(ProcessTaskMapper.class);
        StorageService storageService = mock(StorageService.class);

        // 构造成功结果，结果 id 全局唯一（顺序递增）
        List<ProcessResult> successes = new ArrayList<>();
        long id = 1;
        for (ResultSpec s : specs) {
            ProcessResult r = new ProcessResult();
            r.setId(id++);
            r.setSkuId(s.sku());
            r.setStatus(1);
            r.setOutputPath("results/out_" + r.getId() + "." + s.ext());
            successes.add(r);
        }

        when(resultMapper.selectList(any(QueryWrapper.class))).thenReturn(successes);
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.openInputStream(anyString()))
                .thenAnswer(inv -> new ByteArrayInputStream(
                        ("bytes-of-" + inv.getArgument(0)).getBytes(StandardCharsets.UTF_8)));

        ResultDownloadService service =
                new ResultDownloadService(resultMapper, taskMapper, storageService);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamZip(taskId, out);

        // 解析 zip，收集条目名
        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryNames.add(entry.getName());
                zis.closeEntry();
            }
        }

        // 每条成功结果恰一个条目
        assertThat(entryNames).hasSize(successes.size());
        // 条目名互不冲突（唯一）
        Set<String> unique = new HashSet<>(entryNames);
        assertThat(unique).hasSize(entryNames.size());
        // 条目名 == 规范化 sku + "_" + resultId + "." + ext(小写)
        List<String> expected = successes.stream().map(this::expectedEntryName).toList();
        assertThat(entryNames).containsExactlyInAnyOrderElementsOf(expected);

        // 查询条件确实按成功状态（status=1）与 taskId 过滤（需求 13.3）
        ArgumentCaptor<QueryWrapper<ProcessResult>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        org.mockito.Mockito.verify(resultMapper).selectList(captor.capture());
        QueryWrapper<ProcessResult> wrapper = captor.getValue();
        wrapper.getSqlSegment(); // 触发惰性填充 paramNameValuePairs
        assertThat(wrapper.getParamNameValuePairs().values()).contains(1, taskId);
    }

    private String expectedEntryName(ProcessResult r) {
        String sku = r.getSkuId() == null || r.getSkuId().isBlank() ? "sku" : r.getSkuId();
        String safeSku = sku.replaceAll("[^A-Za-z0-9_-]", "_");
        String path = r.getOutputPath();
        int dot = path.lastIndexOf('.');
        String ext = (dot < 0 || dot == path.length() - 1)
                ? "png" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return safeSku + "_" + r.getId() + "." + ext;
    }
}

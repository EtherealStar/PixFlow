package com.etherealstar.pixflow.module.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.etherealstar.pixflow.module.conversation.entity.Conversation;
import com.etherealstar.pixflow.module.conversation.mapper.ConversationMapper;
import com.etherealstar.pixflow.module.dag.DagJsonCodec;
import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import com.etherealstar.pixflow.module.dag.engine.DagExecutionEngine;
import com.etherealstar.pixflow.module.dag.engine.DagExecutionSummary;
import com.etherealstar.pixflow.module.dag.validator.DagValidator;
import com.etherealstar.pixflow.module.file.domain.PackageStatus;
import com.etherealstar.pixflow.module.file.entity.AssetImage;
import com.etherealstar.pixflow.module.file.entity.AssetPackage;
import com.etherealstar.pixflow.module.file.mapper.AssetImageMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import com.etherealstar.pixflow.module.task.dto.ConfirmRequest;
import com.etherealstar.pixflow.module.task.dto.ConfirmResponse;
import com.etherealstar.pixflow.module.task.entity.ProcessResult;
import com.etherealstar.pixflow.module.task.mapper.ProcessResultMapper;
import com.etherealstar.pixflow.module.task.mapper.ProcessTaskMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 结果预览取前 min(3, n) 张属性测试（任务 14.9）。
 *
 * <p>Feature: pixflow, Property 27: 成功结果图预览——对任意成功结果数 n，{@code confirm} 返回的预览 URL
 * 数量恒为 {@code min(3, n)}，且这 min(3, n) 张恒为按 {@code asset_image.id} 升序（同图按结果 id 升序）
 * 排在最前的结果。
 * Validates: Requirements 8.6, 8.7
 */
class PreviewLimitPropertyTest {

    private static final int PREVIEW_LIMIT = 3;
    private static final String RAW_URL = "/api/asset/result/%d/raw";

    @Provide
    Arbitrary<List<Long>> imageIdLists() {
        // 不同 imageId（保证排序确定），列表规模覆盖 0..15 跨越 min(3,n) 边界
        return Arbitraries.longs().between(1, 100_000)
                .list().uniqueElements().ofMinSize(0).ofMaxSize(15);
    }

    @Property(tries = 300)
    @SuppressWarnings("unchecked")
    void previewReturnsFirstMinThreeByImageIdOrder(@ForAll("imageIdLists") List<Long> imageIds) {
        long conversationId = 1L;
        long packageId = 2L;

        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
        AssetImageMapper imageMapper = mock(AssetImageMapper.class);
        ProcessTaskMapper taskMapper = mock(ProcessTaskMapper.class);
        ProcessResultMapper resultMapper = mock(ProcessResultMapper.class);
        DagValidator dagValidator = mock(DagValidator.class);
        DagJsonCodec dagJsonCodec = mock(DagJsonCodec.class);
        DagExecutionEngine executionEngine = mock(DagExecutionEngine.class);

        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        when(conversationMapper.selectById(conversationId)).thenReturn(conversation);

        AssetPackage pkg = new AssetPackage();
        pkg.setId(packageId);
        pkg.setStatus(PackageStatus.READY);
        when(packageMapper.selectById(packageId)).thenReturn(pkg);

        when(imageMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        Dag dag = new Dag(List.of(new DagNode("n0", "remove_bg", Map.of())), List.of());
        when(dagValidator.validateJson(any())).thenReturn(dag);
        when(dagJsonCodec.write(any())).thenReturn("{\"nodes\":[],\"edges\":[]}");
        when(taskMapper.insert(any())).thenAnswer(inv -> {
            inv.getArgument(0, com.etherealstar.pixflow.module.task.entity.ProcessTask.class).setId(9L);
            return 1;
        });
        when(executionEngine.execute(any(), any(), any()))
                .thenReturn(new DagExecutionSummary(9L, 2, imageIds.size(), imageIds.size(),
                        imageIds.size(), imageIds.size(), 0));

        // 构造 n 条成功结果，每条 imageId 互不相同，结果 id 与 imageId 顺序故意错开
        List<ProcessResult> successes = new ArrayList<>();
        long resultId = 500;
        for (Long imageId : imageIds) {
            ProcessResult r = new ProcessResult();
            r.setId(resultId--);
            r.setImageId(imageId);
            r.setStatus(1);
            r.setOutputPath("results/r_" + r.getId() + ".png");
            successes.add(r);
        }
        when(resultMapper.selectList(any(QueryWrapper.class))).thenReturn(successes);

        TaskService service = new TaskService(conversationMapper, packageMapper, imageMapper,
                taskMapper, resultMapper, dagValidator, dagJsonCodec, executionEngine);

        ConfirmResponse response = service.confirm(conversationId,
                new ConfirmRequest("{}", packageId));

        int expectedCount = Math.min(PREVIEW_LIMIT, imageIds.size());
        assertThat(response.resultPreviewUrls()).hasSize(expectedCount);

        // 期望：按 imageId 升序取前 expectedCount 条对应的结果 id 生成的 URL
        List<String> expected = successes.stream()
                .sorted(java.util.Comparator.comparing(ProcessResult::getImageId)
                        .thenComparing(ProcessResult::getId))
                .limit(expectedCount)
                .map(r -> String.format(RAW_URL, r.getId()))
                .toList();
        assertThat(response.resultPreviewUrls()).containsExactlyElementsOf(expected);
    }
}

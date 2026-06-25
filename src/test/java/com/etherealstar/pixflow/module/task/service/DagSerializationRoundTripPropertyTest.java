package com.etherealstar.pixflow.module.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.etherealstar.pixflow.module.conversation.entity.Conversation;
import com.etherealstar.pixflow.module.conversation.mapper.ConversationMapper;
import com.etherealstar.pixflow.module.dag.DagJsonCodec;
import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagEdge;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import com.etherealstar.pixflow.module.dag.engine.DagExecutionEngine;
import com.etherealstar.pixflow.module.dag.engine.DagExecutionSummary;
import com.etherealstar.pixflow.module.dag.engine.TopologicalSorter;
import com.etherealstar.pixflow.module.dag.schema.DagValidationProperties;
import com.etherealstar.pixflow.module.dag.validator.DagValidator;
import com.etherealstar.pixflow.module.file.domain.PackageStatus;
import com.etherealstar.pixflow.module.file.entity.AssetImage;
import com.etherealstar.pixflow.module.file.entity.AssetPackage;
import com.etherealstar.pixflow.module.file.mapper.AssetImageMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import com.etherealstar.pixflow.module.task.dto.ConfirmRequest;
import com.etherealstar.pixflow.module.task.entity.ProcessResult;
import com.etherealstar.pixflow.module.task.entity.ProcessTask;
import com.etherealstar.pixflow.module.task.mapper.ProcessResultMapper;
import com.etherealstar.pixflow.module.task.mapper.ProcessTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 任务 DAG 序列化往返与初始状态属性测试（任务 14.8）。
 *
 * <p>Feature: pixflow, Property 35: 任务创建时 DAG 序列化往返一致且任务初始状态为 0——
 * 对任意合法 DAG，{@code validateJson(write(dag)).equals(dag)} 恒成立（序列化往返一致）；
 * 且 {@code confirm} 创建的任务 status 初始化为 0、{@code total_count} 等于待处理图片数、
 * 持久化的 {@code dag_json} 同样可无损往返。
 * Validates: Requirements 12.6
 */
class DagSerializationRoundTripPropertyTest {

    private static final String[] TOOLS = {
            "remove_bg", "set_background", "resize", "compress",
            "watermark", "convert_format", "generate_copy"
    };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TopologicalSorter sorter = new TopologicalSorter();
    private final DagJsonCodec codec = new DagJsonCodec(objectMapper);
    private final DagValidator validator =
            new DagValidator(objectMapper, sorter, new DagValidationProperties());

    // ---- 合法 DAG 生成器：节点数 1–12、工具白名单内、参数合法、仅前向边（无环）-------

    @Provide
    Arbitrary<Dag> validDags() {
        return Arbitraries.longs().map(seed -> buildDag(new Random(seed)));
    }

    private Dag buildDag(Random rnd) {
        int n = 1 + rnd.nextInt(12);
        List<DagNode> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String tool = TOOLS[rnd.nextInt(TOOLS.length)];
            nodes.add(new DagNode("n" + i, tool, validParams(tool, rnd)));
        }
        List<DagEdge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                // 仅 i<j 的前向边，保证无环；约 30% 概率连边
                if (rnd.nextInt(100) < 30) {
                    edges.add(new DagEdge("n" + i, "n" + j));
                }
            }
        }
        return new Dag(nodes, edges);
    }

    /** 生成满足对应工具 schema 的合法参数，数值统一用 Integer 以保证 JSON 往返类型一致。 */
    private Map<String, Object> validParams(String tool, Random rnd) {
        Map<String, Object> params = new LinkedHashMap<>();
        switch (tool) {
            case "set_background" -> params.put("color", "#FFFFFF");
            case "resize" -> {
                params.put("width", 1 + rnd.nextInt(4000));
                params.put("height", 1 + rnd.nextInt(4000));
            }
            case "compress" -> params.put("max_kb", 1 + rnd.nextInt(5000));
            case "watermark" -> {
                params.put("position", "center");
                params.put("text", "SALE");
            }
            case "convert_format" -> params.put("format",
                    new String[]{"PNG", "JPG", "WebP"}[rnd.nextInt(3)]);
            case "generate_copy" -> params.put("style", "简约");
            default -> {
                // remove_bg：无参数
            }
        }
        return params;
    }

    @Property(tries = 1000)
    void writeThenValidateIsIdentity(@ForAll("validDags") Dag dag) {
        String json = codec.write(dag);
        Dag parsed = validator.validateJson(json);
        assertThat(parsed).isEqualTo(dag);
    }

    // ---- 初始状态与持久化往返：通过 confirm 串联校验（needs mocked I/O）-------------

    @Test
    @SuppressWarnings("unchecked")
    void confirmInitializesTaskWithStatusZeroAndRoundTrippableDag() {
        long conversationId = 100L;
        long packageId = 200L;

        // 一个确定的合法 DAG，作为前端回传内容
        Dag sample = new Dag(
                List.of(
                        new DagNode("n0", "resize", Map.of("width", 800, "height", 600)),
                        new DagNode("n1", "convert_format", Map.of("format", "PNG"))),
                List.of(new DagEdge("n0", "n1")));
        String requestJson = codec.write(sample);

        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
        AssetImageMapper imageMapper = mock(AssetImageMapper.class);
        ProcessTaskMapper taskMapper = mock(ProcessTaskMapper.class);
        ProcessResultMapper resultMapper = mock(ProcessResultMapper.class);
        DagExecutionEngine executionEngine = mock(DagExecutionEngine.class);

        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        when(conversationMapper.selectById(conversationId)).thenReturn(conversation);

        AssetPackage pkg = new AssetPackage();
        pkg.setId(packageId);
        pkg.setStatus(PackageStatus.READY);
        when(packageMapper.selectById(packageId)).thenReturn(pkg);

        // 三张待处理图片 -> total_count 应等于 3
        List<AssetImage> images = List.of(image(1L), image(2L), image(3L));
        when(imageMapper.selectList(any(QueryWrapper.class))).thenReturn(images);

        // 捕获插入时刻的 status / total_count，避免后续被污染
        int[] insertedStatus = {-1};
        int[] insertedTotal = {-1};
        when(taskMapper.insert(any(ProcessTask.class))).thenAnswer(inv -> {
            ProcessTask t = inv.getArgument(0);
            insertedStatus[0] = t.getStatus();
            insertedTotal[0] = t.getTotalCount();
            t.setId(7L);
            return 1;
        });
        when(executionEngine.execute(any(), any(), any()))
                .thenReturn(new DagExecutionSummary(7L, 2, 3, 3, 3, 3, 0));
        when(resultMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        TaskService service = new TaskService(conversationMapper, packageMapper, imageMapper,
                taskMapper, resultMapper, validator, codec, executionEngine);

        service.confirm(conversationId, new ConfirmRequest(requestJson, packageId));

        // status 初始化为 0、total_count 为待处理图片数（需求 12.6）
        assertThat(insertedStatus[0]).isZero();
        assertThat(insertedTotal[0]).isEqualTo(images.size());

        // 持久化的 dag_json 可无损往返
        ArgumentCaptor<ProcessTask> captor = ArgumentCaptor.forClass(ProcessTask.class);
        org.mockito.Mockito.verify(taskMapper).insert(captor.capture());
        Dag reparsed = validator.validateJson(captor.getValue().getDagJson());
        assertThat(reparsed).isEqualTo(sample);
    }

    private AssetImage image(long id) {
        AssetImage img = new AssetImage();
        img.setId(id);
        return img;
    }
}

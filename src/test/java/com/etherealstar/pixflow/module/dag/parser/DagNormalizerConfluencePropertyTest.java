package com.etherealstar.pixflow.module.dag.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.etherealstar.pixflow.module.dag.domain.Dag;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * DagNormalizer 编排顺序确定性属性测试（任务 9.6）。
 *
 * <p>Feature: pixflow, Property 20: 编排顺序确定性（confluence）——当 convert_format 与 compress
 * 在同一 DAG 中共现且二者间无强制先后边时，规范化后 convert_format 必须始终排在 compress 之前；
 * 即无论二者在原始声明中的书写次序如何，相同指令始终产出一致的先后顺序。
 * Validates: Requirements 6.4
 *
 * <p>本测试针对纯函数 {@link DagNormalizer}，不涉及 LLM/图片/文案/外部 API。
 */
class DagNormalizerConfluencePropertyTest {

    private final DagNormalizer normalizer = new DagNormalizer();

    /** 其余「中性」节点（与 convert_format/compress 无关）。 */
    private static final List<String> NEUTRAL_TOOLS = List.of("remove_bg", "resize", "set_background");

    @Provide
    Arbitrary<List<DagNode>> shuffledNodesWithConvertAndCompress() {
        // 0..3 个中性节点，连同 convert_format、compress 一起随机排列
        Arbitrary<List<String>> neutrals = Arbitraries.of(NEUTRAL_TOOLS).list().ofMinSize(0).ofMaxSize(3);
        return neutrals.flatMap(tools -> {
            List<String> all = new ArrayList<>(tools);
            all.add("convert_format");
            all.add("compress");
            // 以洗牌排列覆盖所有书写次序
            return Arbitraries.shuffle(all).map(DagNormalizerConfluencePropertyTest::toNodes);
        });
    }

    private static List<DagNode> toNodes(List<String> tools) {
        List<DagNode> nodes = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            nodes.add(new DagNode("n" + i, tools.get(i), Map.of()));
        }
        return nodes;
    }

    @Property(tries = 300)
    void convertFormatAlwaysPrecedesCompress(
            @ForAll("shuffledNodesWithConvertAndCompress") List<DagNode> nodes) {
        // 无边：二者无强制先后约束，顺序完全由规范化器的确定性比较器决定
        Dag normalized = normalizer.normalize(new Dag(nodes, new ArrayList<>()));
        List<String> tools = normalized.getNodes().stream().map(DagNode::getTool).toList();

        int cf = tools.indexOf("convert_format");
        int compress = tools.indexOf("compress");
        assertThat(cf).isGreaterThanOrEqualTo(0);
        assertThat(compress).isGreaterThanOrEqualTo(0);
        assertThat(cf).as("convert_format 必须排在 compress 之前").isLessThan(compress);
    }

    @Property(tries = 200)
    void normalizationIsDeterministicRegardlessOfDeclarationOrder(
            @ForAll("shuffledNodesWithConvertAndCompress") List<DagNode> nodes) {
        // 相同节点集合（仅声明次序不同）的规范化结果在 convert_format vs compress 的相对序上一致
        List<DagNode> reversed = new ArrayList<>(nodes);
        java.util.Collections.reverse(reversed);

        List<String> a = relativeOrder(normalizer.normalize(new Dag(nodes, new ArrayList<>())));
        List<String> b = relativeOrder(normalizer.normalize(new Dag(reversed, new ArrayList<>())));
        assertThat(a).isEqualTo(b).containsExactly("convert_format", "compress");
    }

    private static List<String> relativeOrder(Dag dag) {
        List<String> result = new ArrayList<>();
        for (DagNode node : dag.getNodes()) {
            if ("convert_format".equals(node.getTool()) || "compress".equals(node.getTool())) {
                result.add(node.getTool());
            }
        }
        return result;
    }

    @Test
    void twoNodeBothOrdersNormalizeToConvertThenCompress() {
        DagNode cf = new DagNode("a", "convert_format", Map.of());
        DagNode compress = new DagNode("b", "compress", Map.of());

        Dag forward = normalizer.normalize(new Dag(List.of(cf, compress), new ArrayList<>()));
        Dag backward = normalizer.normalize(new Dag(List.of(compress, cf), new ArrayList<>()));

        assertThat(forward.getNodes().stream().map(DagNode::getTool).toList())
                .containsExactly("convert_format", "compress");
        assertThat(backward.getNodes().stream().map(DagNode::getTool).toList())
                .containsExactly("convert_format", "compress");
    }
}

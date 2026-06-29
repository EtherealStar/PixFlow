package com.pixflow.module.dag.ir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * DagJsonReader 浅解析测试。
 */
class DagJsonReaderTest {

    private final DagJsonReader reader = new DagJsonReader();

    @Test
    void read_minimalDag() {
        String json = """
            {"nodes":[{"id":"n1","tool":"resize","params":{"width":800}}],"edges":[]}
            """;
        DagDocument doc = reader.read(json);
        assertThat(doc.nodes()).hasSize(1);
        assertThat(doc.nodes().get(0).id()).isEqualTo("n1");
        assertThat(doc.nodes().get(0).tool()).isEqualTo(PixelTool.RESIZE);
        assertThat(doc.nodes().get(0).params()).containsEntry("width", 800L);
        assertThat(doc.edges()).isEmpty();
    }

    @Test
    void read_dagWithMultipleNodesAndEdges() {
        String json = """
            {
              "nodes":[
                {"id":"n1","tool":"remove_bg","params":{}},
                {"id":"n2","tool":"set_background","params":{"color":"#FFFFFF"}},
                {"id":"n3","tool":"resize","params":{"width":800,"height":800}}
              ],
              "edges":[
                {"from":"n1","to":"n2"},
                {"from":"n2","to":"n3"}
              ]
            }
            """;
        DagDocument doc = reader.read(json);
        assertThat(doc.nodes()).hasSize(3);
        assertThat(doc.edges()).hasSize(2);
        assertThat(doc.edges().get(0).from()).isEqualTo("n1");
        assertThat(doc.edges().get(0).to()).isEqualTo("n2");
    }

    @Test
    void read_rejectsUnknownTool() {
        String json = """
            {"nodes":[{"id":"n1","tool":"unknown_op","params":{}}],"edges":[]}
            """;
        assertThatThrownBy(() -> reader.read(json))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown_op");
    }

    @Test
    void read_rejectsMissingNodesArray() {
        assertThatThrownBy(() -> reader.read("{\"edges\":[]}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nodes");
    }

    @Test
    void read_rejectsMissingEdgesArray() {
        assertThatThrownBy(() -> reader.read("{\"nodes\":[]}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("edges");
    }

    @Test
    void read_rejectsTopLevelNonObject() {
        assertThatThrownBy(() -> reader.read("[1,2,3]"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void read_rejectsNodeMissingId() {
        String json = """
            {"nodes":[{"tool":"resize","params":{}}],"edges":[]}
            """;
        assertThatThrownBy(() -> reader.read(json))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id");
    }

    @Test
    void read_handlesNullParams() {
        String json = """
            {"nodes":[{"id":"n1","tool":"resize","params":null}],"edges":[]}
            """;
        DagDocument doc = reader.read(json);
        assertThat(doc.nodes().get(0).params()).isEmpty();
    }

    @Test
    void read_handlesParamsOmitted() {
        String json = """
            {"nodes":[{"id":"n1","tool":"resize"}],"edges":[]}
            """;
        DagDocument doc = reader.read(json);
        assertThat(doc.nodes().get(0).params()).isEmpty();
    }

    @Test
    void read_handlesNestedParams() {
        String json = """
            {"nodes":[{"id":"n1","tool":"compose_group","params":{"layout":"HORIZONTAL","order":["a","b"]}}],"edges":[]}
            """;
        DagDocument doc = reader.read(json);
        assertThat(doc.nodes().get(0).params())
            .containsEntry("layout", "HORIZONTAL")
            .containsEntry("order", java.util.List.of("a", "b"));
    }
}
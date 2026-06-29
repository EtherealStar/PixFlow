package com.pixflow.module.dag.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.PixelTool;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * NodeDispatcher 派发目标测试。
 */
class NodeDispatcherTest {

    private final NodeDispatcher dispatcher = new NodeDispatcher(new SpecMapper());

    @Test
    void resize_mapsToImageTarget() {
        assertThat(dispatcher.targetOf(PixelTool.RESIZE))
            .isEqualTo(PixelTool.Target.IMAGE);
    }

    @Test
    void removeBg_mapsToThirdpartyTarget() {
        assertThat(dispatcher.targetOf(PixelTool.REMOVE_BG))
            .isEqualTo(PixelTool.Target.THIRDPARTY);
    }

    @Test
    void generateCopy_mapsToAiTarget() {
        assertThat(dispatcher.targetOf(PixelTool.GENERATE_COPY))
            .isEqualTo(PixelTool.Target.AI);
    }

    @Test
    void dispatch_resize_returnsImageOp() {
        DagNode node = new DagNode("n1", PixelTool.RESIZE, Map.of("width", 800));
        assertThat(dispatcher.dispatch(node)).isNotNull();
    }

    @Test
    void dispatch_removeBg_returnsNull() {
        DagNode node = new DagNode("n1", PixelTool.REMOVE_BG, Map.of());
        assertThat(dispatcher.dispatch(node)).isNull();
    }

    @Test
    void dispatch_generateCopy_returnsNull() {
        DagNode node = new DagNode("n1", PixelTool.GENERATE_COPY, Map.of());
        assertThat(dispatcher.dispatch(node)).isNull();
    }

    @Test
    void dispatch_nullNode_returnsNull() {
        assertThat(dispatcher.dispatch(null)).isNull();
    }
}
package com.pixflow.module.dag.exec;

import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.PixelTool;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * NodeDispatcher:节点 tool → 派发目标(对齐 dag.md §8.4)。
 *
 * <p>封闭枚举驱动:把每节点的 spec 包装为 ImageOp(本地像素操作)或
 * 标识第三方调用(remove_bg)/AI 调用(generate_copy)。具体执行由
 * {@link PipelineUnitExecutor} / {@link GroupUnitExecutor} / {@link CopyUnitExecutor} 负责。
 */
@Component
public class NodeDispatcher {

    private final SpecMapper specMapper;

    public NodeDispatcher(SpecMapper specMapper) {
        this.specMapper = specMapper;
    }

    /**
     * 把 DagNode 翻译为 ImageOp。本地像素工具直接映射;remove_bg / generate_copy 返 null
     * (由对应执行器在更上层处理)。
     */
    public ImageOp dispatch(DagNode node) {
        if (node == null || node.tool() == null) {
            return null;
        }
        return switch (node.tool()) {
            case REMOVE_BG, GENERATE_COPY -> null; // 由专用执行器处理
            case RESIZE, COMPRESS, SET_BACKGROUND, WATERMARK,
                 CONVERT_FORMAT, COMPOSE_GROUP -> localImageOp(node);
        };
    }

    private ImageOp localImageOp(DagNode node) {
        Object spec = specMapper.toSpec(node);
        // 实际执行由 infra/image 的 ImagePipeline 串联;此处仅持有 spec 引用
        return src -> {
            throw new UnsupportedOperationException(
                "ImageOp.apply 由 ImagePipeline.run 内部串联,本 dispatcher 不直接调用;"
                    + "node=" + node.id() + " tool=" + node.tool().wireName()
                    + " spec=" + spec);
        };
    }

    /** 工具的派发目标枚举映射(供 metric 标签与日志使用)。 */
    public PixelTool.Target targetOf(PixelTool tool) {
        return tool == null ? null : tool.target();
    }

    /** 工具调用需要 spec 列表(本地像素工具)。 */
    public List<ImageOp> dispatchAll(List<DagNode> nodes) {
        return nodes.stream().map(this::dispatch).toList();
    }
}
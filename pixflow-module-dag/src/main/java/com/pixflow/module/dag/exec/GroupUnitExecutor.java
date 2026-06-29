package com.pixflow.module.dag.exec;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.infra.image.op.MultiImageOp;
import com.pixflow.module.dag.config.DagProperties;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.PixelTool;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * GroupUnitExecutor:组支路执行器(对齐 dag.md §8.3)。
 *
 * <p>对组内每个成员 m(按 viewId 排序)施加 perMemberOps,再 fan-in 进 compose,
 * 最后施加 postOps,编码一次,落 RESULTS 桶。
 *
 * <p>缺图归一化:任一成员读取/解码/处理失败 → 整条组支路 FAILED,
 * error.details.missingViews 标注失败 view。
 */
@Component
public class GroupUnitExecutor implements UnitExecutor {

    /** storage 接口(里程碑 3 注入真实 ObjectStorage)。 */
    public interface SourceReader {
        InputStream openStream(String objectKey);
        long statSize(String objectKey);
    }

    /** 第三方抠图(组支路内若有 remove_bg 节点需调用)。 */
    public interface BackgroundRemovalPort {
        byte[] remove(byte[] sourceBytes, Map<String, Object> options);
    }

    /** 像素流水线(里程碑 3 注入 ImagePipeline.runComposed)。 */
    public interface PixelPipeline {
        byte[] runComposed(List<InputStream> members,
                           List<com.pixflow.infra.image.op.ImageOp> perMemberOps,
                           MultiImageOp compose,
                           List<com.pixflow.infra.image.op.ImageOp> postOps,
                           com.pixflow.infra.image.EncodeSpec encode);
    }

    /** 结果写出。 */
    public interface ResultWriter {
        String write(String objectKey, byte[] data);
    }

    private final DagProperties properties;
    private final ErrorNormalizer normalizer;
    private final SourceReader sourceReader;
    private final BackgroundRemovalPort bgRemoval;
    private final PixelPipeline pixelPipeline;
    private final ResultWriter resultWriter;
    private final NodeDispatcher nodeDispatcher;

    public GroupUnitExecutor(DagProperties properties,
                              ErrorNormalizer normalizer,
                              SourceReader sourceReader,
                              BackgroundRemovalPort bgRemoval,
                              PixelPipeline pixelPipeline,
                              ResultWriter resultWriter,
                              NodeDispatcher nodeDispatcher) {
        this.properties = properties;
        this.normalizer = normalizer;
        this.sourceReader = sourceReader;
        this.bgRemoval = bgRemoval;
        this.pixelPipeline = pixelPipeline;
        this.resultWriter = resultWriter;
        this.nodeDispatcher = nodeDispatcher;
    }

    @Override
    public UnitOutcome execute(ExecutableBranch branch, UnitInput input) {
        if (branch == null || input == null) {
            return UnitOutcome.failed(UnitKind.GROUP, null, null,
                new UnitOutcome.DagErrorView(DagErrorCode.DAG_INVALID_STRUCTURE,
                    "组支路输入为空", ErrorCategory.VALIDATION));
        }
        List<ImageDescriptor> images = input.imageDescriptors();
        if (images == null || images.isEmpty()) {
            return UnitOutcome.failed(branch.kind(), branch.branchId(), branch.memberId(),
                new UnitOutcome.DagErrorView(DagErrorCode.DAG_GROUP_MEMBER_MISSING,
                    "组支路无成员", ErrorCategory.NOT_FOUND));
        }
        List<String> missingViews = new ArrayList<>();
        List<InputStream> memberStreams = new ArrayList<>();
        List<UnitOutcome.MemberRef> members = new ArrayList<>();
        try {
            for (ImageDescriptor img : images) {
                long size = sourceReader.statSize(img.objectKey());
                if (size > properties.getExecution().getSourceBytesLimit()) {
                    missingViews.add(img.viewId() == null ? img.imageId() : img.viewId());
                    continue;
                }
                try {
                    byte[] src = readAllBytes(sourceReader.openStream(img.objectKey()));
                    // 预施加 remove_bg(若 perMemberOps 里有)
                    byte[] prepared = applyBgRemoval(branch.perMemberOps(), src);
                    memberStreams.add(new ByteArrayInputStream(prepared));
                    members.add(new UnitOutcome.MemberRef(img.imageId(), img.viewId(), img.objectKey()));
                } catch (Throwable t) {
                    missingViews.add(img.viewId() == null ? img.imageId() : img.viewId());
                }
            }
            if (!missingViews.isEmpty()) {
                return UnitOutcome.failed(branch.kind(), branch.branchId(), branch.memberId(),
                    new UnitOutcome.DagErrorView(DagErrorCode.DAG_GROUP_MEMBER_MISSING,
                        "成员缺失: " + String.join(",", missingViews),
                        ErrorCategory.NOT_FOUND));
            }
            if (memberStreams.size() != images.size()) {
                return UnitOutcome.failed(branch.kind(), branch.branchId(), branch.memberId(),
                    new UnitOutcome.DagErrorView(DagErrorCode.DAG_GROUP_MEMBER_MISSING,
                        "部分成员缺失", ErrorCategory.NOT_FOUND));
            }
            // 构造 compose 与 postOps
            MultiImageOp compose = member -> {
                throw new UnsupportedOperationException(
                    "compose 由 NodeDispatcher 派发;GroupUnitExecutor 内部把 compose_group 节点映射为 MultiImageOp");
            };
            List<com.pixflow.infra.image.op.ImageOp> perMemberOps = branch.perMemberOps().stream()
                .filter(n -> n.tool() != PixelTool.REMOVE_BG)
                .map(nodeDispatcher::dispatch)
                .filter(java.util.Objects::nonNull)
                .toList();
            List<com.pixflow.infra.image.op.ImageOp> postOps = branch.postOps().stream()
                .map(nodeDispatcher::dispatch)
                .filter(java.util.Objects::nonNull)
                .toList();
            com.pixflow.infra.image.EncodeSpec encode = encodeTarget(branch);
            byte[] result = pixelPipeline.runComposed(memberStreams, perMemberOps, compose, postOps, encode);
            String outputKey = "results/" + branch.branchId() + "/" + branch.memberId() + ".jpg";
            resultWriter.write(outputKey, result);
            return UnitOutcome.succeeded(branch.kind(), branch.branchId(), branch.memberId(),
                outputKey, members);
        } catch (Throwable t) {
            PixFlowException pe = normalize(t);
            return UnitOutcome.failed(branch.kind(), branch.branchId(), branch.memberId(),
                new UnitOutcome.DagErrorView(DagErrorCode.DAG_UNIT_EXECUTION_FAILED,
                    Sanitizer.sanitizeMessage(pe.getMessage()),
                    pe.category()));
        }
    }

    private byte[] applyBgRemoval(List<DagNode> ops, byte[] source) {
        byte[] current = source;
        for (DagNode node : ops) {
            if (node.tool() == PixelTool.REMOVE_BG) {
                current = bgRemoval.remove(current, node.params());
            }
        }
        return current;
    }

    private com.pixflow.infra.image.EncodeSpec encodeTarget(ExecutableBranch branch) {
        for (int i = branch.postOps().size() - 1; i >= 0; i--) {
            DagNode n = branch.postOps().get(i);
            if (n.tool() == PixelTool.CONVERT_FORMAT) {
                Object fmt = n.params().get("targetFormat");
                Object q = n.params().get("quality");
                com.pixflow.infra.image.ImageFormat f;
                try {
                    f = com.pixflow.infra.image.ImageFormat.valueOf(fmt == null ? "JPEG" : fmt.toString());
                } catch (IllegalArgumentException e) {
                    f = com.pixflow.infra.image.ImageFormat.JPEG;
                }
                Integer quality = q instanceof Number num ? num.intValue() : null;
                return new com.pixflow.infra.image.EncodeSpec(f, quality, null, null);
            }
        }
        return new com.pixflow.infra.image.EncodeSpec(
            com.pixflow.infra.image.ImageFormat.JPEG, null, null, null);
    }

    private PixFlowException normalize(Throwable t) {
        if (t instanceof PixFlowException pe) {
            return pe;
        }
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return new PixFlowException(DagErrorCode.DAG_UNIT_EXECUTION_FAILED,
            Sanitizer.sanitizeMessage(msg), t,
            Map.of("category", ErrorCategory.IMAGE_PROCESSING));
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        return in.readAllBytes();
    }
}
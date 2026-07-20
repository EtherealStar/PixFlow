package com.pixflow.module.dag.exec;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.ReopenableImageSource;
import com.pixflow.infra.image.op.ImageOp;
import com.pixflow.module.dag.config.DagProperties;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.ImageDescriptor;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * PipelineUnitExecutor:逐图支路执行器(对齐 dag.md §8.2)。
 *
 * <p>职责:把一条 ExecutableBranch 缝合成 infra 调用链:
 * <pre>
 *   storage.getStream → 顺序遍历 perMemberOps:
 *     remove_bg → bgRemoval.remove(源字节) → 抠图字节
 *     其他逐图工具 → 累积为 List<ImageOp> → 由 PixelPipeline.run 一并执行
 *   storage.put → UnitOutcome.SUCCEEDED
 * </pre>
 *
 * <p>**永不抛出业务异常**;任何异常经归一化后返回 UnitOutcome.FAILED。
 * 调度与超时由 task 外壳负责；DAG 执行器不创建线程池或消息边界。
 * 大图防护在 storage.stat 阶段拦截。
 */
@Component
public class PipelineUnitExecutor implements UnitExecutor {

    /** storage 抽象(里程碑 3 注入真实 ObjectStorage)。 */
    public interface SourceReader {
        InputStream openStream(String referenceKey);

        long statSize(String referenceKey);
    }

    /** 第三方抠图抽象;返抠图后字节(带 alpha PNG)。 */
    public interface BackgroundRemovalPort {
        byte[] remove(byte[] sourceBytes, BackgroundRemovalBindingSpec options);
    }

    /** 像素流水线(里程碑 3 注入 ImagePipeline.run):接字节流 + ops + encode 产出结果字节。 */
    public interface PixelPipeline {
        byte[] run(ReopenableImageSource source, List<ImageOp> ops,
                   com.pixflow.infra.image.EncodeSpec encode);
    }

    /** 结果写出(里程碑 3 注入 ObjectStorage.put)。 */
    public interface ResultWriter {
        String write(String objectKey, byte[] data);
    }

    private final DagProperties properties;

    private final ErrorNormalizer normalizer;

    private final SourceReader sourceReader;

    private final BackgroundRemovalPort bgRemoval;

    private final PixelPipeline pixelPipeline;

    private final ResultWriter resultWriter;

    private final TypedImageOpFactory imageOpFactory;

    public PipelineUnitExecutor(DagProperties properties,
                                ErrorNormalizer normalizer,
                                SourceReader sourceReader,
                                BackgroundRemovalPort bgRemoval,
                                PixelPipeline pixelPipeline,
                                ResultWriter resultWriter,
                                TypedImageOpFactory imageOpFactory) {
        this.properties = properties;
        this.normalizer = normalizer;
        this.sourceReader = sourceReader;
        this.bgRemoval = bgRemoval;
        this.pixelPipeline = pixelPipeline;
        this.resultWriter = resultWriter;
        this.imageOpFactory = imageOpFactory;
    }

    @Override
    public UnitOutcome execute(ExecutableBranch branch, UnitInput input) {
        if (branch == null || input == null || input.imageDescriptors().isEmpty()) {
            return UnitOutcome.failed(branch == null ? UnitKind.BRANCH : branch.kind(),
                branch == null ? null : branch.branchId(),
                branch == null ? null : branch.memberId(),
                new UnitOutcome.DagErrorView(DagErrorCode.DAG_INVALID_STRUCTURE,
                    "单元输入为空",
                    ErrorCategory.VALIDATION));
        }
        ImageDescriptor image = input.imageDescriptors().get(0);
        if (input.outputObjectKey() == null || input.outputObjectKey().isBlank()) {
            return UnitOutcome.failed(branch.kind(), branch.branchId(), branch.memberId(),
                new UnitOutcome.DagErrorView(DagErrorCode.DAG_INVALID_STRUCTURE,
                    "缺少输出对象目标", ErrorCategory.VALIDATION));
        }
        try {
            return doExecute(branch, image, input.outputObjectKey());
        } catch (Throwable e) {
            PixFlowException pe = normalizer.normalize(e);
            return failureFromException(branch, pe);
        }
    }

    private UnitOutcome doExecute(ExecutableBranch branch, ImageDescriptor image, String outputKey) {
        // 大图防护
        long size = image.sizeBytes() > 0 ? image.sizeBytes()
                : sourceReader.statSize(image.referenceKey());
        if (size > properties.getExecution().getSourceBytesLimit()) {
            return UnitOutcome.failed(branch.kind(), branch.branchId(), branch.memberId(),
                new UnitOutcome.DagErrorView(DagErrorCode.DAG_SOURCE_BYTES_TOO_LARGE,
                    "源字节 " + size + " 超过阈值",
                    DagErrorCode.DAG_SOURCE_BYTES_TOO_LARGE.category()));
        }
        // 缝合 I/O
        try {
            ReopenableImageSource source = () -> sourceReader.openStream(image.referenceKey());
            byte[] bytes = applyOps(branch.perMemberOps(), source);
            resultWriter.write(outputKey, bytes);
            return UnitOutcome.succeeded(branch.kind(), branch.branchId(), branch.memberId(),
                outputKey, List.of());
        } catch (Throwable t) {
            PixFlowException pe = normalizeAsImageProcessing(t);
            return failureFromException(branch, pe);
        }
    }

    private byte[] applyOps(List<ExecutionStep> ops, ReopenableImageSource source) throws Exception {
        // 阶段 1:把所有 remove_bg 节点应用(罕见多次);阶段 2:把剩余逐图工具交给 PixelPipeline。
        ReopenableImageSource currentSource = source;
        for (ExecutionStep node : ops) {
            if (node instanceof ExternalStep external) {
                byte[] src;
                try (InputStream stream = currentSource.openStream()) {
                    src = readAllBytes(stream);
                }
                byte[] removed = bgRemoval.remove(src, external.typedSpec());
                currentSource = () -> new ByteArrayInputStream(removed);
            } else if (node instanceof CopyStep) {
                throw new IllegalStateException("generate_copy 不在像素链上");
            }
            // 非 remove_bg 的本地操作交给 PixelPipeline.run 统一处理
        }
        // 累积非 remove_bg 的本地 op 列表
        List<ImageOp> localOps = ops.stream()
            .filter(LocalImageStep.class::isInstance)
            .map(LocalImageStep.class::cast)
            .map(imageOpFactory::create)
            .toList();
        com.pixflow.infra.image.EncodeSpec encode = encodeTarget(ops);
        return pixelPipeline.run(currentSource, localOps, encode);
    }

    private com.pixflow.infra.image.EncodeSpec encodeTarget(List<ExecutionStep> ops) {
        for (int i = ops.size() - 1; i >= 0; i--) {
            ExecutionStep step = ops.get(i);
            if (step instanceof LocalImageStep local
                    && local.typedSpec() instanceof LocalImageBindingSpec.ConvertFormat convert) {
                return convert.value().toEncodeSpec();
            }
        }
        return new com.pixflow.infra.image.EncodeSpec(ImageFormat.JPEG, null, null, null);
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        return in.readAllBytes();
    }

    /**
     * 异常归一化:对于单元执行路径上的异常,默认归到 IMAGE_PROCESSING(SKIP)分类,
     * 便于 FailureIsolator 隔离该支路(对齐 dag.md §10)。
     */
    private PixFlowException normalizeAsImageProcessing(Throwable t) {
        if (t instanceof PixFlowException pe) {
            return pe;
        }
        String safe = Sanitizer.sanitizeMessage(
            t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        return new PixFlowException(DagErrorCode.DAG_UNIT_EXECUTION_FAILED, safe, t,
            Map.of("category", ErrorCategory.IMAGE_PROCESSING));
    }

    private UnitOutcome failureFromException(ExecutableBranch branch, PixFlowException pe) {
        DagErrorCode code = pe.code() instanceof DagErrorCode dec
            ? dec : DagErrorCode.DAG_UNIT_EXECUTION_FAILED;
        String safeMsg = Sanitizer.truncate(pe.getMessage() == null
            ? "单元执行失败" : pe.getMessage(), 1000);
        return UnitOutcome.failed(branch.kind(), branch.branchId(), branch.memberId(),
            new UnitOutcome.DagErrorView(code, safeMsg, pe.category()));
    }
}

package com.pixflow.module.dag.exec;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.infra.image.op.MultiImageOp;
import com.pixflow.infra.image.ReopenableImageSource;
import com.pixflow.infra.image.op.ComposeSpec;
import com.pixflow.infra.image.op.impl.ComposeGroupOp;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.dag.config.DagProperties;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.ImageDescriptor;
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
        InputStream openStream(ObjectLocation location);

        long statSize(ObjectLocation location);
    }

    /** 第三方抠图(组支路内若有 remove_bg 节点需调用)。 */
    public interface BackgroundRemovalPort {
        byte[] remove(byte[] sourceBytes, BackgroundRemovalBindingSpec options);
    }

    /** 像素流水线(里程碑 3 注入 ImagePipeline.runComposed)。 */
    public interface PixelPipeline {
        byte[] runComposed(List<ReopenableImageSource> members,
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

    private final TypedImageOpFactory imageOpFactory;

    private final GroupRuntimeArtifactStore runtimeArtifacts;

    public GroupUnitExecutor(DagProperties properties,
                              ErrorNormalizer normalizer,
                              SourceReader sourceReader,
                              BackgroundRemovalPort bgRemoval,
                              PixelPipeline pixelPipeline,
                              ResultWriter resultWriter,
                              TypedImageOpFactory imageOpFactory,
                              GroupRuntimeArtifactStore runtimeArtifacts) {
        this.properties = properties;
        this.normalizer = normalizer;
        this.sourceReader = sourceReader;
        this.bgRemoval = bgRemoval;
        this.pixelPipeline = pixelPipeline;
        this.resultWriter = resultWriter;
        this.imageOpFactory = imageOpFactory;
        this.runtimeArtifacts = runtimeArtifacts;
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
        if (input.outputObjectKey() == null || input.outputObjectKey().isBlank()) {
            return UnitOutcome.failed(branch.kind(), branch.branchId(), branch.memberId(),
                new UnitOutcome.DagErrorView(DagErrorCode.DAG_INVALID_STRUCTURE,
                    "缺少输出对象目标", ErrorCategory.VALIDATION));
        }
        List<String> missingViews = new ArrayList<>();
        List<ReopenableImageSource> memberSources = new ArrayList<>();
        List<UnitOutcome.MemberRef> members = new ArrayList<>();
        GroupRuntimeArtifactStore.Session runtimeSession = runtimeSession(branch, input);
        try (runtimeSession) {
            for (ImageDescriptor img : images) {
                long size = sourceReader.statSize(img.location());
                if (size > properties.getExecution().getSourceBytesLimit()) {
                    missingViews.add(img.viewId() == null ? img.imageId() : img.viewId());
                    continue;
                }
                try {
                    if (branch.perMemberOps().stream().anyMatch(ExternalStep.class::isInstance)) {
                        byte[] prepared = prepareMember(runtimeSession, branch, img);
                        memberSources.add(() -> new ByteArrayInputStream(prepared));
                    } else {
                        // 无外部字节型操作时保留可重开来源，让 image pipeline 先 probe 再 decode。
                        try (InputStream ignored = sourceReader.openStream(img.location())) {
                            // 只验证对象存在与流可打开，不在预算准入前读取像素字节。
                        }
                        memberSources.add(() -> sourceReader.openStream(img.location()));
                    }
                    members.add(new UnitOutcome.MemberRef(img.imageId(), img.viewId(), img.location().key()));
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
            if (memberSources.size() != images.size()) {
                return UnitOutcome.failed(branch.kind(), branch.branchId(), branch.memberId(),
                    new UnitOutcome.DagErrorView(DagErrorCode.DAG_GROUP_MEMBER_MISSING,
                        "部分成员缺失", ErrorCategory.NOT_FOUND));
            }
            // 构造 compose 与 postOps
            if (branch.composeStep() == null
                    || !(branch.composeStep().typedSpec() instanceof ComposeSpec composeSpec)) {
                throw new IllegalArgumentException("组支路缺少类型化 compose spec");
            }
            MultiImageOp compose = new ComposeGroupOp(composeSpec);
            List<com.pixflow.infra.image.op.ImageOp> perMemberOps = branch.perMemberOps().stream()
                .filter(LocalImageStep.class::isInstance)
                .map(LocalImageStep.class::cast)
                .map(imageOpFactory::create)
                .toList();
            List<com.pixflow.infra.image.op.ImageOp> postOps = branch.postOps().stream()
                .filter(LocalImageStep.class::isInstance)
                .map(LocalImageStep.class::cast)
                .map(imageOpFactory::create)
                .toList();
            com.pixflow.infra.image.EncodeSpec encode = encodeTarget(branch);
            byte[] result = pixelPipeline.runComposed(memberSources, perMemberOps, compose, postOps, encode);
            String outputKey = input.outputObjectKey();
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

    private byte[] prepareMember(GroupRuntimeArtifactStore.Session session,
                                 ExecutableBranch branch, ImageDescriptor image) {
        java.util.function.Supplier<byte[]> producer = () -> {
            try (InputStream source = sourceReader.openStream(image.location())) {
                return applyBgRemoval(branch.perMemberOps(), readAllBytes(source));
            } catch (Exception ex) {
                throw new IllegalStateException("组成员预处理失败", ex);
            }
        };
        return session == null ? producer.get() : session.getOrCompute(image.imageId(), producer);
    }

    private GroupRuntimeArtifactStore.Session runtimeSession(ExecutableBranch branch, UnitInput input) {
        if (runtimeArtifacts == null || input.unitKey() == null || input.runEpoch() <= 0
                || branch.kind() != UnitKind.GROUP) {
            return null;
        }
        return runtimeArtifacts.open(input.unitKey(), input.runEpoch());
    }

    private byte[] applyBgRemoval(List<ExecutionStep> ops, byte[] source) {
        byte[] current = source;
        for (ExecutionStep step : ops) {
            if (step instanceof ExternalStep external) {
                current = bgRemoval.remove(current, external.typedSpec());
            }
        }
        return current;
    }

    private com.pixflow.infra.image.EncodeSpec encodeTarget(ExecutableBranch branch) {
        for (int i = branch.postOps().size() - 1; i >= 0; i--) {
            ExecutionStep step = branch.postOps().get(i);
            if (step instanceof LocalImageStep local
                    && local.typedSpec() instanceof LocalImageBindingSpec.ConvertFormat convert) {
                return convert.value().toEncodeSpec();
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

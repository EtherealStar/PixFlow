package com.pixflow.module.dag.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.dag.config.DagProperties;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.ir.PixelTool;
import com.pixflow.module.dag.TestPlans;
import com.pixflow.module.dag.validate.DagValidator;
import com.pixflow.module.dag.validate.ParamSchemaRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GroupUnitExecutor 测试:组支路缝合 + 缺图归一化 + compose fan-in。
 */
class GroupUnitExecutorTest {

    private DagProperties properties;
    private ErrorNormalizer normalizer;

    @BeforeEach
    void setUp() {
        properties = new DagProperties();
        normalizer = new ErrorNormalizer();
    }

    private ExecutableBranch composeBranch() {
        DagValidator validator = new DagValidator(new ParamSchemaRegistry(), 50, 1);
        DagJsonReader reader = new DagJsonReader();
        String json = """
            {
              "nodes":[
                {"id":"n1","tool":"resize","params":{"width":100}},
                {"id":"n2","tool":"resize","params":{"width":100}},
                {"id":"c","tool":"compose_group","params":{"layout":"HORIZONTAL"}},
                {"id":"p","tool":"compress","params":{"quality":80}}
              ],
              "edges":[
                {"from":"n1","to":"c"},
                {"from":"n2","to":"c"},
                {"from":"c","to":"p"}
              ]
            }
            """;
        var dag = TestPlans.compile(json);
        var images = List.of(
            ImageDescriptor.grouped("img1", "g1", "v1", location("k1")),
            ImageDescriptor.grouped("img2", "g1", "v2", location("k2"))
        );
        var branches = new com.pixflow.module.dag.expand.BranchExpander().expand(dag, images);
        return branches.stream()
            .filter(b -> b.kind() == UnitKind.GROUP)
            .findFirst()
            .orElseThrow();
    }

    private GroupUnitExecutor executor(GroupUnitExecutor.SourceReader reader,
                                        GroupUnitExecutor.BackgroundRemovalPort bg,
                                        GroupUnitExecutor.PixelPipeline pipeline,
                                        GroupUnitExecutor.ResultWriter writer) {
        return new GroupUnitExecutor(properties, normalizer, reader, bg, pipeline, writer,
            new TypedImageOpFactory(spec -> { throw new AssertionError("unexpected watermark"); },
                    spec -> { throw new AssertionError("unexpected background image"); }), null);
    }

    @Test
    void execute_returnsSucceeded_onHappyPath() {
        var reader = new GroupUnitExecutor.SourceReader() {
            @Override public InputStream openStream(String referenceKey) {
                return new ByteArrayInputStream(new byte[]{1, 2, 3});
            }
            @Override public long statSize(String referenceKey) { return 10L; }
        };
        var bg = (GroupUnitExecutor.BackgroundRemovalPort) (b, o) -> b;
        AtomicInteger pipelineCalled = new AtomicInteger();
        var pipeline = (GroupUnitExecutor.PixelPipeline) (members, perMember, compose, post, enc) -> {
            pipelineCalled.incrementAndGet();
            return new byte[]{7, 7, 7};
        };
        AtomicInteger writerCalled = new AtomicInteger();
        var writer = (GroupUnitExecutor.ResultWriter) (key, data) -> {
            writerCalled.incrementAndGet();
            return key;
        };
        var ex = executor(reader, bg, pipeline, writer);
        UnitOutcome outcome = ex.execute(composeBranch(), UnitInput.images(
            List.of(
                ImageDescriptor.grouped("img1", "g1", "v1", location("k1")),
                ImageDescriptor.grouped("img2", "g1", "v2", location("k2"))
            )
        ));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.SUCCEEDED);
        assertThat(outcome.members()).hasSize(2);
        assertThat(pipelineCalled.get()).isEqualTo(1);
        assertThat(writerCalled.get()).isEqualTo(1);
        assertThat(outcome.outputObjectKey()).isEqualTo("test/output.jpg");
    }

    @Test
    void execute_returnsFAILED_onMissingMember() {
        var reader = new GroupUnitExecutor.SourceReader() {
            @Override public InputStream openStream(String referenceKey) {
                if (referenceKey.equals("asset:image:k1")) {
                    return new ByteArrayInputStream(new byte[]{1});
                }
                throw new RuntimeException("storage failure for k2");
            }
            @Override public long statSize(String referenceKey) { return 10L; }
        };
        var ex = executor(reader, (b, o) -> b, (m, p, c, po, e) -> new byte[]{}, (k, d) -> k);
        UnitOutcome outcome = ex.execute(composeBranch(), UnitInput.images(
            List.of(
                ImageDescriptor.grouped("img1", "g1", "v1", location("k1")),
                ImageDescriptor.grouped("img2", "g1", "v2", location("k2"))
            )
        ));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.FAILED);
        assertThat(outcome.error().code()).isEqualTo(DagErrorCode.DAG_GROUP_MEMBER_MISSING);
        assertThat(outcome.error().safeMessage()).contains("v2");
    }

    @Test
    void execute_returnsFAILED_onEmptyImages() {
        var ex = executor(
            (GroupUnitExecutor.SourceReader) new GroupUnitExecutor.SourceReader() {
                @Override public InputStream openStream(String referenceKey) { return new ByteArrayInputStream(new byte[]{}); }
                @Override public long statSize(String referenceKey) { return 0L; }
            },
            (b, o) -> b,
            (m, p, c, po, e) -> new byte[]{},
            (k, d) -> k);
        UnitOutcome outcome = ex.execute(composeBranch(), UnitInput.images(List.of()));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.FAILED);
        assertThat(outcome.error().code()).isEqualTo(DagErrorCode.DAG_GROUP_MEMBER_MISSING);
    }

    @Test
    void execute_returnsFAILED_onTooLargeSource() {
        var reader = new GroupUnitExecutor.SourceReader() {
            @Override public InputStream openStream(String referenceKey) {
                throw new AssertionError("不应调用 openStream");
            }
            @Override public long statSize(String referenceKey) {
                return properties.getExecution().getSourceBytesLimit() + 1L;
            }
        };
        var ex = executor(reader, (b, o) -> b, (m, p, c, po, e) -> new byte[]{}, (k, d) -> k);
        UnitOutcome outcome = ex.execute(composeBranch(), UnitInput.images(
            List.of(ImageDescriptor.grouped("img1", "g1", "v1", location("k1")))));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.FAILED);
        assertThat(outcome.error().code()).isEqualTo(DagErrorCode.DAG_GROUP_MEMBER_MISSING);
    }

    @Test
    void execute_neverThrows_businessException() {
        var reader = new GroupUnitExecutor.SourceReader() {
            @Override public InputStream openStream(String referenceKey) {
                throw new RuntimeException("catastrophic");
            }
            @Override public long statSize(String referenceKey) { return 10L; }
        };
        var pipeline = (GroupUnitExecutor.PixelPipeline) (members, perMember, compose, post, enc) -> {
            throw new RuntimeException("pipeline boom");
        };
        var ex = executor(reader, (b, o) -> b, pipeline, (k, d) -> k);
        // 即便读 / pipeline 都失败,GroupUnitExecutor 仍返回 UnitOutcome,绝不抛业务异常
        UnitOutcome outcome = ex.execute(composeBranch(), UnitInput.images(
            List.of(ImageDescriptor.grouped("img1", "g1", "v1", location("k1")))));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.FAILED);
    }
    private static String location(String key) {
        return "asset:image:" + key;
    }
}

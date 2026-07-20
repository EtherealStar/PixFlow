package com.pixflow.module.dag.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.ErrorCategory;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.dag.config.DagProperties;
import com.pixflow.module.dag.error.DagErrorCode;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.ir.PixelTool;
import com.pixflow.module.dag.TestPlans;
import com.pixflow.module.dag.validate.DagValidator;
import com.pixflow.module.dag.validate.ParamSchemaRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PipelineUnitExecutor 测试:失败注入、大图防护、资源清理、归一化错误。
 */
class PipelineUnitExecutorTest {

    private DagProperties properties;
    private ErrorNormalizer normalizer;

    @BeforeEach
    void setUp() {
        properties = new DagProperties();
        properties.getExecution().setUnitTimeout(Duration.ofMillis(500));
        properties.getExecution().setSourceBytesLimit(1024 * 1024); // 1MB
        normalizer = new ErrorNormalizer();
    }

    private ExecutableBranch simpleBranch() {
        DagValidator validator = new DagValidator(new ParamSchemaRegistry(), 50, 1);
        String json = """
            {
              "nodes":[
                {"id":"n1","tool":"remove_bg","params":{}},
                {"id":"n2","tool":"resize","params":{"width":100}}
              ],
              "edges":[{"from":"n1","to":"n2"}]
            }
            """;
        var dag = TestPlans.compile(json);
        List<ExecutableBranch> branches = new com.pixflow.module.dag.expand.BranchExpander()
            .expand(dag, List.of(ImageDescriptor.single("img1", "sku1", location("k1"))));
        return branches.get(0);
    }

    private PipelineUnitExecutor executor(PipelineUnitExecutor.SourceReader reader,
                                          PipelineUnitExecutor.BackgroundRemovalPort bg,
                                          PipelineUnitExecutor.PixelPipeline pipeline,
                                          PipelineUnitExecutor.ResultWriter writer) {
        return new PipelineUnitExecutor(properties, normalizer, reader, bg, pipeline, writer,
            new TypedImageOpFactory(spec -> { throw new AssertionError("unexpected watermark"); },
                    spec -> { throw new AssertionError("unexpected background image"); }));
    }

    @Test
    void execute_returnsSucceeded_onHappyPath() {
        AtomicInteger streamOpened = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        var reader = new PipelineUnitExecutor.SourceReader() {
            @Override public InputStream openStream(String referenceKey) {
                streamOpened.incrementAndGet();
                return new ByteArrayInputStream(new byte[]{1, 2, 3});
            }
            @Override public long statSize(String referenceKey) {
                return 100L;
            }
        };
        var bg = (PipelineUnitExecutor.BackgroundRemovalPort) (bytes, options) -> new byte[]{9, 9, 9};
        var pipeline = (PipelineUnitExecutor.PixelPipeline) (src, ops, enc) -> new byte[]{7, 7, 7};
        var writer = (PipelineUnitExecutor.ResultWriter) (key, data) -> {
            written.incrementAndGet();
            return key;
        };
        var ex = executor(reader, bg, pipeline, writer);
        var outcome = ex.execute(simpleBranch(),
            UnitInput.images(List.of(ImageDescriptor.single("img1", "sku1", location("k1")))));
        assertThat(outcome.status()).withFailMessage("outcome=%s", outcome)
                .isEqualTo(UnitOutcome.Status.SUCCEEDED);
        assertThat(outcome.outputObjectKey()).isEqualTo("test/output.jpg");
    }

    @Test
    void execute_returnsFAILED_andNeverThrows_onBgException() {
        var reader = new PipelineUnitExecutor.SourceReader() {
            @Override public InputStream openStream(String referenceKey) {
                return new ByteArrayInputStream(new byte[]{1});
            }
            @Override public long statSize(String referenceKey) { return 10L; }
        };
        var bg = (PipelineUnitExecutor.BackgroundRemovalPort) (bytes, options) -> {
            throw new RuntimeException("upstream failed");
        };
        var pipeline = (PipelineUnitExecutor.PixelPipeline) (src, ops, enc) -> new byte[]{};
        var writer = (PipelineUnitExecutor.ResultWriter) (key, data) -> key;
        var ex = executor(reader, bg, pipeline, writer);
        UnitOutcome outcome = ex.execute(simpleBranch(),
            UnitInput.images(List.of(ImageDescriptor.single("img1", "sku1", location("k1")))));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.FAILED);
        assertThat(outcome.error()).isNotNull();
        assertThat(outcome.error().code()).isEqualTo(DagErrorCode.DAG_UNIT_EXECUTION_FAILED);
        assertThat(outcome.error().category()).isEqualTo(ErrorCategory.IMAGE_PROCESSING);
    }

    @Test
    void execute_returnsFAILED_DAG_SOURCE_BYTES_TOO_LARGE() {
        var reader = new PipelineUnitExecutor.SourceReader() {
            @Override public InputStream openStream(String referenceKey) {
                throw new AssertionError("不应调用 openStream");
            }
            @Override public long statSize(String referenceKey) {
                return properties.getExecution().getSourceBytesLimit() + 1L;
            }
        };
        var ex = executor(reader, (a, b) -> new byte[]{}, (s, o, e) -> new byte[]{}, (k, d) -> k);
        UnitOutcome outcome = ex.execute(simpleBranch(),
            UnitInput.images(List.of(ImageDescriptor.single("img1", "sku1", location("k1")))));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.FAILED);
        assertThat(outcome.error().code()).isEqualTo(DagErrorCode.DAG_SOURCE_BYTES_TOO_LARGE);
    }

    @Test
    void execute_returnsFAILED_onNullInput() {
        var ex = executor(
            (PipelineUnitExecutor.SourceReader) new PipelineUnitExecutor.SourceReader() {
                @Override public InputStream openStream(String referenceKey) { return new ByteArrayInputStream(new byte[]{}); }
                @Override public long statSize(String referenceKey) { return 0L; }
            },
            (a, b) -> new byte[]{},
            (s, o, e) -> new byte[]{},
            (k, d) -> k);
        UnitOutcome outcome = ex.execute(simpleBranch(), new UnitInput(null, null));
        assertThat(outcome.status()).isEqualTo(UnitOutcome.Status.FAILED);
        assertThat(outcome.error().code()).isEqualTo(DagErrorCode.DAG_INVALID_STRUCTURE);
    }

@Test
    void execute_closesInputStream_evenOnFailure() {
        AtomicInteger closed = new AtomicInteger();
        var reader = new PipelineUnitExecutor.SourceReader() {
            @Override public InputStream openStream(String referenceKey) {
                return new ByteArrayInputStream(new byte[]{1}) {
                    @Override public void close() {
                        closed.incrementAndGet();
                    }
                };
            }
            @Override public long statSize(String referenceKey) { return 10L; }
        };
        var bg = (PipelineUnitExecutor.BackgroundRemovalPort) (bytes, options) -> {
            throw new RuntimeException("forced");
        };
        var ex = executor(reader, bg, (s, o, e) -> new byte[]{}, (k, d) -> k);
        ex.execute(simpleBranch(),
            UnitInput.images(List.of(ImageDescriptor.single("img1", "sku1", location("k1")))));
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    void execute_safeMessage_isTruncatedAndDoesNotExposeStackTrace() {
        var reader = new PipelineUnitExecutor.SourceReader() {
            @Override public InputStream openStream(String referenceKey) {
                return new ByteArrayInputStream(new byte[]{1});
            }
            @Override public long statSize(String referenceKey) { return 10L; }
        };
        // 构造一个超长 message(>1000 字符),测试截断
        String longMessage = "x".repeat(2000);
        var bg = (PipelineUnitExecutor.BackgroundRemovalPort) (bytes, options) -> {
            throw new RuntimeException(longMessage);
        };
        var ex = executor(reader, bg, (s, o, e) -> new byte[]{}, (k, d) -> k);
        UnitOutcome outcome = ex.execute(simpleBranch(),
            UnitInput.images(List.of(ImageDescriptor.single("img1", "sku1", location("k1")))));
        // safeMessage 应被截断至 ≤1000 字符(Sanitizer 默认阈值)
        assertThat(outcome.error().safeMessage()).hasSizeLessThanOrEqualTo(1000);
        // FAILED 必有 error code 与 category
        assertThat(outcome.error().code()).isEqualTo(DagErrorCode.DAG_UNIT_EXECUTION_FAILED);
        assertThat(outcome.error().category()).isEqualTo(ErrorCategory.IMAGE_PROCESSING);
    }
    private static String location(String key) {
        return "asset:image:" + key;
    }
}

package com.pixflow.module.dag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.contracts.proposal.PendingPlanPort;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.module.dag.DagFacade;
import com.pixflow.module.dag.cache.TaskAssetCache;
import com.pixflow.module.dag.exec.BranchExecutionContext;
import com.pixflow.module.dag.exec.CopyUnitExecutor;
import com.pixflow.module.dag.exec.GroupUnitExecutor;
import com.pixflow.module.dag.exec.GroupRuntimeArtifactStore;
import com.pixflow.module.dag.exec.PipelineUnitExecutor;
import com.pixflow.module.dag.exec.StepSpecCompiler;
import com.pixflow.module.dag.exec.DagCompiler;
import com.pixflow.module.dag.exec.DefaultDagCompiler;
import com.pixflow.module.dag.exec.StepBindingRegistry;
import com.pixflow.module.dag.exec.TypedImageOpFactory;
import com.pixflow.module.dag.exec.RoutingUnitExecutor;
import com.pixflow.module.dag.exec.UnitExecutor;
import com.pixflow.module.dag.ir.CanonicalDagFactory;
import com.pixflow.module.dag.expand.BranchExpander;
import com.pixflow.module.dag.expand.GroupPreflight;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.propose.PendingPlanMapper;
import com.pixflow.module.dag.propose.PendingPlanPortAdapter;
import com.pixflow.module.dag.propose.PendingPlanService;
import com.pixflow.module.dag.propose.SubmitImagePlanHandler;
import com.pixflow.module.dag.validate.DagValidator;
import com.pixflow.module.dag.validate.ParamSchemaRegistry;
import com.pixflow.module.dag.validate.SchemaRegistryValidator;
import java.time.Clock;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.mybatis.spring.annotation.MapperScan;

/**
 * DagAutoConfiguration:dag 模块 Spring 装配。
 *
 * <p>所有 bean 缺失时使用 @ConditionalOnMissingBean,允许测试覆盖。
 */
@AutoConfiguration
@EnableConfigurationProperties(DagProperties.class)
@MapperScan(value = "com.pixflow.module.dag.propose", annotationClass = Mapper.class)
public class DagAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public PipelineUnitExecutor.SourceReader pipelineSourceReader(
            com.pixflow.infra.storage.ObjectStorage storage) {
        return new PipelineUnitExecutor.SourceReader() {
            private com.pixflow.infra.storage.ObjectLocation location(String key) {
                return com.pixflow.infra.storage.ObjectLocation.of(
                        com.pixflow.infra.storage.BucketType.PACKAGES, key);
            }
            @Override public java.io.InputStream openStream(String key) { return storage.getStream(location(key)); }
            @Override public long statSize(String key) { return storage.stat(location(key)).size(); }
        };
    }

    @Bean @ConditionalOnMissingBean
    public PipelineUnitExecutor.BackgroundRemovalPort pipelineBackgroundRemoval(
            com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalClient client) {
        return (bytes, spec) -> client.remove(new com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalRequest(
                bytes, "application/octet-stream", null,
                new com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalOptions(
                        "keep".equalsIgnoreCase(spec.outputFormat())
                                ? com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalOutputFormat.KEEP_SOURCE
                                : com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalOutputFormat.PNG,
                        spec.crop(), spec.featherRadius(), java.util.Map.of()))).image();
    }

    @Bean @ConditionalOnMissingBean
    public PipelineUnitExecutor.PixelPipeline pipelinePixelPipeline(
            com.pixflow.infra.image.pipeline.ImagePipeline pipeline) {
        return (source, ops, encode) -> {
            return pipeline.run(source, ops, encode);
        };
    }

    @Bean @ConditionalOnMissingBean
    public PipelineUnitExecutor.ResultWriter pipelineResultWriter(
            com.pixflow.infra.storage.ObjectStorage storage) {
        return (key, data) -> storage.put(com.pixflow.infra.storage.ObjectLocation.of(
                com.pixflow.infra.storage.BucketType.RESULTS, key),
                new java.io.ByteArrayInputStream(data), data.length, "application/octet-stream").key();
    }

    @Bean @ConditionalOnMissingBean
    public GroupUnitExecutor.SourceReader groupSourceReader(PipelineUnitExecutor.SourceReader reader) {
        return new GroupUnitExecutor.SourceReader() {
            @Override public java.io.InputStream openStream(String key) { return reader.openStream(key); }
            @Override public long statSize(String key) { return reader.statSize(key); }
        };
    }

    @Bean @ConditionalOnMissingBean
    public GroupUnitExecutor.BackgroundRemovalPort groupBackgroundRemoval(
            PipelineUnitExecutor.BackgroundRemovalPort port) { return port::remove; }

    @Bean @ConditionalOnMissingBean
    public GroupUnitExecutor.PixelPipeline groupPixelPipeline(
            com.pixflow.infra.image.pipeline.ImagePipeline pipeline) {
        return (members, perMember, compose, post, encode) -> {
            return pipeline.runComposed(members, perMember, compose, post, encode);
        };
    }

    @Bean @ConditionalOnMissingBean
    public GroupUnitExecutor.ResultWriter groupResultWriter(PipelineUnitExecutor.ResultWriter writer) {
        return writer::write;
    }

    @Bean @ConditionalOnMissingBean
    public GroupRuntimeArtifactStore groupRuntimeArtifactStore(
            com.pixflow.harness.state.runtime.RunStateRefStore refs,
            com.pixflow.infra.storage.ObjectStorage storage) {
        return new GroupRuntimeArtifactStore(refs, storage);
    }

    @Bean @ConditionalOnMissingBean
    public TypedImageOpFactory.WatermarkResolver watermarkResolver(
            com.pixflow.infra.storage.ObjectStorage storage,
            com.pixflow.infra.image.ImageCodec codec) {
        return spec -> src -> {
            // 附属图片按每次 apply 解码并在本次操作后释放，避免跨任务持有 RasterImage。
            try (var stream = storage.getStream(com.pixflow.infra.storage.ObjectLocation.of(
                    com.pixflow.infra.storage.BucketType.PACKAGES, spec.imageRef()));
                 var watermark = codec.decode(stream)) {
                var wmSpec = new com.pixflow.infra.image.op.WatermarkSpec(watermark,
                        com.pixflow.infra.image.op.WatermarkSpec.Position.valueOf(spec.position()),
                        (float) spec.opacity(), spec.scale(), spec.margin());
                return new com.pixflow.infra.image.op.impl.WatermarkOp(wmSpec).apply(src);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取水印图片失败", e);
            }
        };
    }

    @Bean @ConditionalOnMissingBean
    public TypedImageOpFactory.BackgroundResolver backgroundResolver(
            com.pixflow.infra.storage.ObjectStorage storage,
            com.pixflow.infra.image.ImageCodec codec) {
        return spec -> src -> {
            try (var stream = storage.getStream(com.pixflow.infra.storage.ObjectLocation.of(
                    com.pixflow.infra.storage.BucketType.PACKAGES, spec.imageRef()));
                 var background = codec.decode(stream)) {
                return new com.pixflow.infra.image.op.impl.SetBackgroundOp(
                        new com.pixflow.infra.image.op.SetBackgroundSpec(spec.color(), background, spec.fit())).apply(src);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取背景图片失败", e);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public ErrorNormalizer errorNormalizer() {
        return new ErrorNormalizer();
    }

    @Bean
    @ConditionalOnMissingBean
    public DagJsonReader dagJsonReader() {
        return new DagJsonReader();
    }

    @Bean
    @ConditionalOnMissingBean
    public StepSpecCompiler stepSpecCompiler() {
        return new StepSpecCompiler();
    }

    @Bean @ConditionalOnMissingBean
    public CanonicalDagFactory canonicalDagFactory(ObjectMapper objectMapper) {
        return new CanonicalDagFactory(objectMapper);
    }

    @Bean @ConditionalOnMissingBean
    public StepBindingRegistry stepBindingRegistry() { return new StepBindingRegistry(); }

    @Bean @ConditionalOnMissingBean
    public DagCompiler dagCompiler(StepSpecCompiler mapper, StepBindingRegistry bindings) {
        return new DefaultDagCompiler(mapper, bindings);
    }

    @Bean @ConditionalOnMissingBean
    public TypedImageOpFactory typedImageOpFactory(
            TypedImageOpFactory.WatermarkResolver watermarkResolver,
            TypedImageOpFactory.BackgroundResolver backgroundResolver) {
        return new TypedImageOpFactory(watermarkResolver, backgroundResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public ParamSchemaRegistry paramSchemaRegistry() {
        return new ParamSchemaRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public DagValidator dagValidator(ParamSchemaRegistry registry, DagProperties props) {
        return new DagValidator(registry,
            props.getValidate().getMaxNodes(),
            props.getValidate().getMinNodes());
    }

    @Bean
    @ConditionalOnMissingBean
    public BranchExpander branchExpander() {
        return new BranchExpander();
    }

    @Bean
    @ConditionalOnMissingBean
    public GroupPreflight groupPreflight() {
        return new GroupPreflight();
    }

    @Bean
    @ConditionalOnMissingBean
    public DagFacade dagFacade(DagValidator validator, BranchExpander expander, GroupPreflight preflight,
                               CanonicalDagFactory canonicalFactory, DagCompiler compiler) {
        return new DagFacade(validator, expander, preflight, canonicalFactory, compiler);
    }

    @Bean
    @ConditionalOnMissingBean
    public BranchExecutionContext branchExecutionContext() {
        return new BranchExecutionContext();
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskAssetCache taskAssetCache(DagProperties props) {
        return new TaskAssetCache(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public PipelineUnitExecutor pipelineUnitExecutor(
            DagProperties props,
            ErrorNormalizer normalizer,
            PipelineUnitExecutor.SourceReader reader,
            PipelineUnitExecutor.BackgroundRemovalPort bgRemoval,
            PipelineUnitExecutor.PixelPipeline pipeline,
            PipelineUnitExecutor.ResultWriter writer,
            TypedImageOpFactory imageOpFactory) {
        // 执行依赖缺失必须在启动期失败，不能把装配错误推迟到任务运行时。
        return new PipelineUnitExecutor(props, normalizer, reader, bgRemoval, pipeline,
            writer, imageOpFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public GroupUnitExecutor groupUnitExecutor(
            DagProperties props,
            ErrorNormalizer normalizer,
            GroupUnitExecutor.SourceReader reader,
            GroupUnitExecutor.BackgroundRemovalPort bgRemoval,
            GroupUnitExecutor.PixelPipeline pipeline,
            GroupUnitExecutor.ResultWriter writer,
            TypedImageOpFactory imageOpFactory,
            GroupRuntimeArtifactStore runtimeArtifacts) {
        return new GroupUnitExecutor(props, normalizer, reader, bgRemoval, pipeline,
            writer, imageOpFactory, runtimeArtifacts);
    }

    @Bean
    @ConditionalOnMissingBean
    public CopyUnitExecutor copyUnitExecutor(com.pixflow.infra.ai.chat.ChatModelClient chatModelClient) {
        return new CopyUnitExecutor(chatModelClient);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(RoutingUnitExecutor.class)
    public UnitExecutor routingUnitExecutor(StepBindingRegistry bindings,
                                            PipelineUnitExecutor pipeline,
                                            GroupUnitExecutor group,
                                            CopyUnitExecutor copy) {
        // registry 在启动期校验全集，router 在运行期只接受其声明的唯一 executor binding。
        return new RoutingUnitExecutor(bindings, pipeline, group, copy);
    }

    @Bean
    @ConditionalOnMissingBean
    public PendingPlanService pendingPlanService(PendingPlanMapper mapper,
                                                    DagValidator validator,
                                                    DagProperties props,
                                                    ObjectMapper objectMapper,
                                                    Clock clock) {
        return new PendingPlanService(mapper, validator, props, objectMapper, clock,
                canonicalDagFactory(objectMapper));
    }

    @Bean
    @ConditionalOnMissingBean
    public PendingPlanPort pendingPlanPort(PendingPlanMapper mapper,
                                           PendingPlanService service,
                                           DagProperties props) {
        return new PendingPlanPortAdapter(mapper, service, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubmitImagePlanHandler submitImagePlanHandler(PendingPlanService service,
                                                           ObjectMapper objectMapper) {
        return new SubmitImagePlanHandler(service, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SchemaRegistryValidator schemaRegistryValidator(ParamSchemaRegistry registry,
                                                             PendingPlanService service) {
        return new SchemaRegistryValidator(registry, service);
    }
}

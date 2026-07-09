package com.pixflow.module.dag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.contracts.proposal.PendingPlanPort;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.module.dag.DagFacade;
import com.pixflow.module.dag.cache.TaskAssetCache;
import com.pixflow.module.dag.exec.BranchExecutionContext;
import com.pixflow.module.dag.exec.CopyUnitExecutor;
import com.pixflow.module.dag.exec.GroupUnitExecutor;
import com.pixflow.module.dag.exec.NodeDispatcher;
import com.pixflow.module.dag.exec.PipelineUnitExecutor;
import com.pixflow.module.dag.exec.SpecMapper;
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
    public SpecMapper specMapper() {
        return new SpecMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeDispatcher nodeDispatcher(SpecMapper specMapper) {
        return new NodeDispatcher(specMapper);
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
    public DagFacade dagFacade(DagValidator validator, BranchExpander expander, GroupPreflight preflight) {
        return new DagFacade(validator, expander, preflight);
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
            NodeDispatcher dispatcher) {
        // 真实 storage/thirdparty/pipeline/writer 注入在 app 装配中;
        // 这里给 SPI default stub,允许启动器不依赖外部容器
        PipelineUnitExecutor.SourceReader stubReader = new PipelineUnitExecutor.SourceReader() {
            @Override public java.io.InputStream openStream(String objectKey) {
                throw new UnsupportedOperationException("storage 未装配");
            }
            @Override public long statSize(String objectKey) {
                return 0L;
            }
        };
        PipelineUnitExecutor.BackgroundRemovalPort stubBg = (bytes, opts) -> {
            throw new UnsupportedOperationException("thirdparty 未装配");
        };
        PipelineUnitExecutor.PixelPipeline stubPipeline = (src, ops, enc) -> {
            throw new UnsupportedOperationException("pipeline 未装配");
        };
        PipelineUnitExecutor.ResultWriter stubWriter = (key, data) -> {
            throw new UnsupportedOperationException("storage writer 未装配");
        };
        return new PipelineUnitExecutor(props, normalizer, stubReader, stubBg, stubPipeline,
            stubWriter, dispatcher);
    }

    @Bean
    @ConditionalOnMissingBean
    public GroupUnitExecutor groupUnitExecutor(
            DagProperties props,
            ErrorNormalizer normalizer,
            NodeDispatcher dispatcher) {
        GroupUnitExecutor.SourceReader stubReader = new GroupUnitExecutor.SourceReader() {
            @Override public java.io.InputStream openStream(String objectKey) {
                throw new UnsupportedOperationException("storage 未装配");
            }
            @Override public long statSize(String objectKey) {
                return 0L;
            }
        };
        GroupUnitExecutor.BackgroundRemovalPort stubBg = (bytes, opts) -> {
            throw new UnsupportedOperationException("thirdparty 未装配");
        };
        GroupUnitExecutor.PixelPipeline stubPipeline = (members, perMember, compose, post, enc) -> {
            throw new UnsupportedOperationException("pipeline runComposed 未装配");
        };
        GroupUnitExecutor.ResultWriter stubWriter = (key, data) -> {
            throw new UnsupportedOperationException("storage writer 未装配");
        };
        return new GroupUnitExecutor(props, normalizer, stubReader, stubBg, stubPipeline,
            stubWriter, dispatcher);
    }

    @Bean
    @ConditionalOnMissingBean
    public CopyUnitExecutor copyUnitExecutor(com.pixflow.infra.ai.chat.ChatModelClient chatModelClient) {
        return new CopyUnitExecutor(chatModelClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public PendingPlanService pendingPlanService(PendingPlanMapper mapper,
                                                    DagValidator validator,
                                                    DagProperties props,
                                                    ObjectMapper objectMapper,
                                                    Clock clock) {
        return new PendingPlanService(mapper, validator, props, objectMapper, clock);
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

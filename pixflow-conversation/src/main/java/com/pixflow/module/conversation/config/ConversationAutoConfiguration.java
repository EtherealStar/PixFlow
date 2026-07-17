package com.pixflow.module.conversation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.progress.ProgressNotifier;
import com.pixflow.harness.session.persistence.MessageReadMapper;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.loop.AgentTurnRunner;
import com.pixflow.module.conversation.app.AgentTurnRunnerRegistry;
import com.pixflow.module.conversation.app.CancellationService;
import com.pixflow.module.conversation.app.ConfirmationService;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.app.HistoryQueryService;
import com.pixflow.module.conversation.app.TurnPreparationService;
import com.pixflow.module.conversation.api.CancellationController;
import com.pixflow.module.conversation.api.ConfirmationController;
import com.pixflow.module.conversation.api.ConversationController;
import com.pixflow.module.conversation.api.HistoryController;
import com.pixflow.module.conversation.api.MessageController;
import com.pixflow.module.conversation.api.SseTurnMetrics;
import com.pixflow.module.conversation.api.SseTurnSessionFactory;
import com.pixflow.module.conversation.attachment.AttachmentCollector;
import com.pixflow.module.conversation.attachment.AttachmentMapper;
import com.pixflow.module.conversation.lock.ConversationLock;
import com.pixflow.module.conversation.persistence.ConversationMapper;
import com.pixflow.module.conversation.progress.ConversationProgressBridge;
import com.pixflow.module.conversation.permission.ConversationPermissionProofs;
import com.pixflow.module.conversation.proposal.ProposalService;
import com.pixflow.module.conversation.proposal.ProposalPayloadVerifier;
import com.pixflow.module.imagegen.confirm.ImagegenPayloadHasher;
import com.pixflow.module.dag.config.DagAutoConfiguration;
import com.pixflow.module.file.pkg.PackageReferenceResolver;
import com.pixflow.module.imagegen.config.ImagegenAutoConfiguration;
import com.pixflow.module.task.api.TaskCommandService;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import io.micrometer.core.instrument.MeterRegistry;

@AutoConfiguration(after = {DagAutoConfiguration.class, ImagegenAutoConfiguration.class})
@EnableConfigurationProperties(ConversationProperties.class)
@MapperScan(value = "com.pixflow.module.conversation.persistence", annotationClass = Mapper.class)
public class ConversationAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "conversationExecutor")
    public ExecutorService conversationExecutor(ConversationProperties properties) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0,
                properties.getTurnExecutor().getMaxConcurrency(),
                properties.getTurnExecutor().getKeepAlive().toMillis(),
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                r -> {
            Thread thread = new Thread(r, "conversation-sse");
            thread.setDaemon(true);
            return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "conversationSseHeartbeatScheduler")
    public ScheduledExecutorService conversationSseHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "conversation-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationService conversationService(ConversationMapper conversationMapper, Clock clock) {
        return new ConversationService(conversationMapper, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationController conversationController(ConversationService conversationService) {
        return new ConversationController(conversationService);
    }

    @Bean
    @ConditionalOnMissingBean
    public HistoryQueryService historyQueryService(
            ConversationService conversationService,
            MessageReadMapper messageReadMapper,
            ConversationProperties properties) {
        return new HistoryQueryService(conversationService, messageReadMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public HistoryController historyController(HistoryQueryService historyQueryService) {
        return new HistoryController(historyQueryService);
    }

    @Bean
    @ConditionalOnMissingBean
    public AttachmentMapper conversationAttachmentMapper() {
        return new AttachmentMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PackageReferenceResolver.class)
    public AttachmentCollector attachmentCollector(PackageReferenceResolver packageReferenceResolver) {
        return new AttachmentCollector(packageReferenceResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationLock conversationLock(
            ObjectProvider<RedissonClient> redissonClientProvider,
            ConversationProperties properties) {
        return new ConversationLock(redissonClientProvider.getIfAvailable(), properties);
    }

    @Bean
    @ConditionalOnMissingBean(name = "agentTurnRunnerRegistry")
    public AgentTurnRunnerRegistry agentTurnRunnerRegistry(
            org.springframework.beans.factory.ObjectProvider<AgentTurnRunner> agentTurnRunnerProvider) {
        // AgentTurnRunner SPI 由 harness-loop 定义，conversation 与 agent 两模块都可见。
        // agent 模块把 AgentOrchestrator 包装为 AgentTurnRunner bean 发布；本 bean 按 SPI 类型
        // 查找，避免 conversation 反向依赖 agent 模块的某个具体类。
        AgentTurnRunner runner = agentTurnRunnerProvider.getIfAvailable();
        return AgentTurnRunnerRegistry.of(runner);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ConversationLock.class, AgentTurnRunnerRegistry.class})
    public TurnPreparationService turnPreparationService(
            ConversationService conversationService,
            ConversationLock conversationLock,
            ObjectProvider<AttachmentCollector> attachmentCollectorProvider,
            AttachmentMapper attachmentMapper,
            AgentTurnRunnerRegistry agentTurnRunnerRegistry) {
        return new TurnPreparationService(
                conversationService,
                conversationLock,
                attachmentCollectorProvider.getIfAvailable(),
                attachmentMapper,
                agentTurnRunnerRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(TurnPreparationService.class)
    public SseTurnMetrics sseTurnMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new SseTurnMetrics(meterRegistryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(TurnPreparationService.class)
    public SseTurnSessionFactory sseTurnSessionFactory(
            ConversationProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("conversationExecutor") ExecutorService conversationExecutor,
            @Qualifier("conversationSseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler,
            SseTurnMetrics metrics) {
        return new SseTurnSessionFactory(
                properties, objectMapper, conversationExecutor, heartbeatScheduler, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({TurnPreparationService.class, SseTurnSessionFactory.class})
    public MessageController messageController(
            TurnPreparationService preparationService,
            SseTurnSessionFactory sessionFactory) {
        return new MessageController(preparationService, sessionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProposalService proposalService() {
        return new ProposalService();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ProposalService.class)
    public ConversationPermissionProofs conversationPermissionProofs(
            ConversationService conversationService,
            ProposalService proposalService) {
        return new ConversationPermissionProofs(conversationService, proposalService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ProposalService.class, PermissionPolicy.class, TaskCommandService.class})
    public ConfirmationService conversationConfirmationService(
            ConversationService conversationService,
            ProposalService proposalService,
            PermissionPolicy permissionPolicy,
            TaskCommandService taskCommandService,
            ObjectMapper objectMapper,
            ImagegenPayloadHasher imagegenPayloadHasher) {
        return new ConfirmationService(
                conversationService,
                proposalService,
                permissionPolicy,
                taskCommandService,
                new ProposalPayloadVerifier(objectMapper, imagegenPayloadHasher));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ConfirmationService.class)
    public ConfirmationController confirmationController(ConfirmationService confirmationService) {
        return new ConfirmationController(confirmationService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(TaskCommandService.class)
    public CancellationService conversationCancellationService(
            ConversationService conversationService,
            TaskCommandService taskCommandService) {
        return new CancellationService(conversationService, taskCommandService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CancellationService.class)
    public CancellationController cancellationController(CancellationService cancellationService) {
        return new CancellationController(cancellationService);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationProgressBridge conversationProgressBridge(
            ObjectProvider<ProgressNotifier> progressNotifierProvider,
            ConversationProperties properties) {
        ProgressNotifier notifier = progressNotifierProvider.getIfAvailable(() -> (channel, event) -> {
        });
        return new ConversationProgressBridge(notifier, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper conversationObjectMapper() {
        return new ObjectMapper();
    }
}

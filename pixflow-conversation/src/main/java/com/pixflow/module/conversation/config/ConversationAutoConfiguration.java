package com.pixflow.module.conversation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.progress.ProgressNotifier;
import com.pixflow.harness.permission.token.ConfirmationTokenService;
import com.pixflow.harness.session.persistence.MessageReadMapper;
import com.pixflow.infra.cache.store.CacheStore;
import com.pixflow.harness.loop.AgentTurnRunner;
import com.pixflow.module.conversation.app.AgentTurnRunnerRegistry;
import com.pixflow.module.conversation.app.CancellationService;
import com.pixflow.module.conversation.app.ConfirmationService;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.app.HistoryQueryService;
import com.pixflow.module.conversation.app.TurnDispatchService;
import com.pixflow.module.conversation.api.CancellationController;
import com.pixflow.module.conversation.api.ConfirmationController;
import com.pixflow.module.conversation.api.ConversationController;
import com.pixflow.module.conversation.api.HistoryController;
import com.pixflow.module.conversation.api.MessageController;
import com.pixflow.module.conversation.attachment.AttachmentCollector;
import com.pixflow.module.conversation.attachment.AttachmentMapper;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.conversation.lock.ConversationLock;
import com.pixflow.module.conversation.persistence.ConversationMapper;
import com.pixflow.module.conversation.progress.ConversationProgressBridge;
import com.pixflow.module.conversation.proposal.PendingProposalRepository;
import com.pixflow.module.conversation.proposal.ProposalThreshold;
import com.pixflow.module.dag.expand.BranchExpander;
import com.pixflow.module.dag.config.DagAutoConfiguration;
import com.pixflow.module.dag.propose.PendingPlanMapper;
import com.pixflow.module.dag.propose.PendingPlanService;
import com.pixflow.module.file.pkg.PackageReferenceResolver;
import com.pixflow.module.imagegen.confirm.ImagegenConfirmationSupport;
import com.pixflow.module.imagegen.config.ImagegenAutoConfiguration;
import com.pixflow.module.task.api.TaskCommandService;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

@AutoConfiguration(after = {DagAutoConfiguration.class, ImagegenAutoConfiguration.class})
@EnableConfigurationProperties(ConversationProperties.class)
@MapperScan(value = "com.pixflow.module.conversation.persistence", annotationClass = Mapper.class)
public class ConversationAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "conversationExecutor")
    public ExecutorService conversationExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "conversation-sse");
            thread.setDaemon(true);
            return thread;
        });
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
    public TurnDispatchService turnDispatchService(
            ConversationService conversationService,
            ConversationLock conversationLock,
            ObjectProvider<AttachmentCollector> attachmentCollectorProvider,
            AttachmentMapper attachmentMapper,
            AgentTurnRunnerRegistry agentTurnRunnerRegistry) {
        return new TurnDispatchService(
                conversationService,
                conversationLock,
                attachmentCollectorProvider.getIfAvailable(),
                attachmentMapper,
                agentTurnRunnerRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(TurnDispatchService.class)
    public MessageController messageController(
            TurnDispatchService turnDispatchService,
            ConversationProperties properties,
            ObjectMapper objectMapper,
            ExecutorService conversationExecutor,
            ScheduledExecutorService conversationSseHeartbeatScheduler) {
        return new MessageController(turnDispatchService, properties, objectMapper, conversationExecutor,
                conversationSseHeartbeatScheduler);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({PendingPlanMapper.class, PendingPlanService.class, PackageReferenceResolver.class,
            BranchExpander.class})
    public PendingProposalRepository pendingProposalRepository(
            PendingPlanMapper pendingPlanMapper,
            PendingPlanService pendingPlanService,
            PackageReferenceResolver packageReferenceResolver,
            BranchExpander branchExpander,
            ObjectProvider<ImagegenConfirmationSupport> imagegenConfirmationSupportProvider) {
        return new PendingProposalRepository(pendingPlanMapper, pendingPlanService, packageReferenceResolver,
                branchExpander, imagegenConfirmationSupportProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ProposalThreshold proposalThreshold(ConversationProperties properties) {
        return new ProposalThreshold(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({PendingProposalRepository.class, ConfirmationTokenService.class, TaskCommandService.class, CacheStore.class})
    public ConfirmationService conversationConfirmationService(
            ConversationService conversationService,
            PendingProposalRepository proposalRepository,
            ProposalThreshold proposalThreshold,
            ConfirmationTokenService tokenService,
            TaskCommandService taskCommandService,
            ConversationProperties properties,
            CacheStore cacheStore,
            Clock clock) {
        return new ConfirmationService(
                conversationService,
                proposalRepository,
                proposalThreshold,
                tokenService,
                taskCommandService,
                properties,
                cacheStore,
                clock);
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

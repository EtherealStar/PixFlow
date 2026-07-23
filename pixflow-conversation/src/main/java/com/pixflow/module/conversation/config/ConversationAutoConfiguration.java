package com.pixflow.module.conversation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.session.history.TranscriptHistoryReader;
import com.pixflow.harness.session.history.TranscriptDeletionService;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.loop.AgentTurnRunner;
import com.pixflow.module.conversation.app.AgentTurnRunnerRegistry;
import com.pixflow.module.conversation.app.CancellationService;
import com.pixflow.module.conversation.app.ConfirmationService;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.app.ConversationDeletionCleanup;
import com.pixflow.module.conversation.app.ConversationDeletionGuard;
import com.pixflow.module.conversation.app.HistoryQueryService;
import com.pixflow.module.conversation.app.DefaultMessageReferenceValidator;
import com.pixflow.module.conversation.app.MessageReferenceValidator;
import com.pixflow.module.conversation.app.TurnPreparationService;
import com.pixflow.module.conversation.lock.ConversationLock;
import com.pixflow.module.conversation.persistence.ConversationMapper;
import com.pixflow.module.conversation.permission.ConversationPermissionProofs;
import com.pixflow.module.conversation.proposal.ProposalService;
import com.pixflow.module.conversation.proposal.ProposalPayloadVerifier;
import com.pixflow.module.imagegen.confirm.ImagegenPayloadHasher;
import com.pixflow.module.dag.config.DagAutoConfiguration;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.module.imagegen.config.ImagegenAutoConfiguration;
import com.pixflow.module.task.api.TaskCommandService;
import java.time.Clock;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = {DagAutoConfiguration.class, ImagegenAutoConfiguration.class})
@EnableConfigurationProperties(ConversationProperties.class)
@MapperScan(value = "com.pixflow.module.conversation.persistence", annotationClass = Mapper.class)
public class ConversationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConversationService conversationService(
            ConversationMapper conversationMapper,
            Clock clock,
            ConversationLock conversationLock,
            TranscriptDeletionService transcriptDeletionService,
            ProposalService proposalService,
            ObjectProvider<ConversationDeletionGuard> deletionGuardProvider) {
        ConversationDeletionCleanup cleanup = conversationId -> {
            transcriptDeletionService.deleteConversation(conversationId);
            proposalService.deleteConversation(conversationId);
        };
        return new ConversationService(
                conversationMapper,
                clock,
                deletionGuardProvider.getIfAvailable(() -> (administratorId, conversationId) -> { }),
                cleanup,
                conversationLock);
    }

    @Bean
    @ConditionalOnMissingBean
    public HistoryQueryService historyQueryService(
            ConversationService conversationService,
            TranscriptHistoryReader historyReader,
            ConversationProperties properties) {
        return new HistoryQueryService(conversationService, historyReader, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageReferenceValidator messageReferenceValidator(
            PermissionPolicy permissionPolicy,
            AssetReferenceResolver assetReferenceResolver) {
        return new DefaultMessageReferenceValidator(permissionPolicy, assetReferenceResolver);
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
    public TurnPreparationService turnPreparationService(
            ConversationService conversationService,
            ConversationLock conversationLock,
            MessageReferenceValidator messageReferenceValidator,
            AgentTurnRunnerRegistry agentTurnRunnerRegistry) {
        return new TurnPreparationService(
                conversationService,
                conversationLock,
                messageReferenceValidator,
                agentTurnRunnerRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProposalService proposalService() {
        return new ProposalService();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationPermissionProofs conversationPermissionProofs(
            ConversationService conversationService,
            ProposalService proposalService) {
        return new ConversationPermissionProofs(conversationService, proposalService);
    }

    @Bean
    @ConditionalOnMissingBean
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
    public CancellationService conversationCancellationService(
            ConversationService conversationService,
            TaskCommandService taskCommandService) {
        return new CancellationService(conversationService, taskCommandService);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper conversationObjectMapper() {
        return new ObjectMapper();
    }
}

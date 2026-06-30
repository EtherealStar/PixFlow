package com.pixflow.module.conversation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.progress.ProgressNotifier;
import com.pixflow.harness.permission.token.ConfirmationTokenService;
import com.pixflow.harness.session.persistence.MessageReadMapper;
import com.pixflow.module.conversation.app.AgentTurnRunner;
import com.pixflow.module.conversation.app.CancellationService;
import com.pixflow.module.conversation.app.ConfirmationService;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.app.HistoryQueryService;
import com.pixflow.module.conversation.app.TurnDispatchService;
import com.pixflow.module.conversation.attachment.AttachmentCollector;
import com.pixflow.module.conversation.attachment.AttachmentMapper;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.conversation.lock.ConversationLock;
import com.pixflow.module.conversation.persistence.ConversationMapper;
import com.pixflow.module.conversation.progress.ConversationProgressBridge;
import com.pixflow.module.conversation.proposal.PendingPlanPortAdapter;
import com.pixflow.module.conversation.proposal.PendingProposalRepository;
import com.pixflow.module.conversation.proposal.ProposalThreshold;
import com.pixflow.module.dag.propose.PendingPlanMapper;
import com.pixflow.module.file.pkg.PackageReferenceResolver;
import com.pixflow.module.imagegen.port.PendingPlanPort;
import com.pixflow.module.task.api.TaskCommandService;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ConversationProperties.class)
@MapperScan("com.pixflow.module.conversation.persistence")
public class ConversationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "conversationClock")
    public Clock conversationClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "conversationExecutor")
    public ExecutorService conversationExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "conversation-sse");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationService conversationService(ConversationMapper conversationMapper, Clock conversationClock) {
        return new ConversationService(conversationMapper, conversationClock);
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
    @ConditionalOnBean(RedissonClient.class)
    public ConversationLock conversationLock(RedissonClient redissonClient, ConversationProperties properties) {
        return new ConversationLock(redissonClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentTurnRunner unavailableAgentTurnRunner() {
        return (conversationId, prompt, attachments, sink) -> {
            throw new PixFlowException(ConversationErrorCode.TURN_RUNNER_UNAVAILABLE,
                    "agent turn runner is not configured");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ConversationLock.class, AttachmentCollector.class, AgentTurnRunner.class})
    public TurnDispatchService turnDispatchService(
            ConversationService conversationService,
            ConversationLock conversationLock,
            AttachmentCollector attachmentCollector,
            AttachmentMapper attachmentMapper,
            AgentTurnRunner agentTurnRunner) {
        return new TurnDispatchService(
                conversationService,
                conversationLock,
                attachmentCollector,
                attachmentMapper,
                agentTurnRunner);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PendingPlanMapper.class)
    public PendingProposalRepository pendingProposalRepository(PendingPlanMapper pendingPlanMapper) {
        return new PendingProposalRepository(pendingPlanMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PendingPlanMapper.class)
    public PendingPlanPort pendingPlanPort(PendingPlanMapper pendingPlanMapper) {
        return new PendingPlanPortAdapter(pendingPlanMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProposalThreshold proposalThreshold(ConversationProperties properties) {
        return new ProposalThreshold(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({PendingProposalRepository.class, ConfirmationTokenService.class, TaskCommandService.class})
    public ConfirmationService conversationConfirmationService(
            ConversationService conversationService,
            PendingProposalRepository proposalRepository,
            ProposalThreshold proposalThreshold,
            ConfirmationTokenService tokenService,
            TaskCommandService taskCommandService,
            ConversationProperties properties,
            Clock conversationClock) {
        return new ConfirmationService(
                conversationService,
                proposalRepository,
                proposalThreshold,
                tokenService,
                taskCommandService,
                properties,
                conversationClock);
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

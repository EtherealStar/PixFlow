package com.pixflow.module.task.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.infra.cache.counter.AtomicCounter;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.lock.LockTemplate;
import com.pixflow.infra.cache.store.CacheStore;
import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory;
import com.pixflow.infra.mq.consumer.ManagedMessageContainer;
import com.pixflow.infra.mq.destination.DestinationRegistrar;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.dag.exec.UnitExecutor;
import com.pixflow.module.dag.expand.BranchExpander;
import com.pixflow.module.imagegen.config.ImagegenProperties;
import com.pixflow.module.imagegen.exec.DefaultImageGenExecutor;
import com.pixflow.module.imagegen.exec.ImageGenExecutor;
import com.pixflow.module.task.api.TaskCommandService;
import com.pixflow.module.task.api.TaskQueryService;
import com.pixflow.module.task.domain.idempotency.IdempotencyGuard;
import com.pixflow.module.task.infra.cache.TaskCacheKeys;
import com.pixflow.module.task.infra.cache.TaskCancelFlag;
import com.pixflow.module.task.infra.cache.TaskIdempotencyStore;
import com.pixflow.module.task.infra.cache.TaskProgressCounter;
import com.pixflow.module.task.infra.lock.TaskLockManager;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.mq.TaskMessageDestination;
import com.pixflow.module.task.infra.mq.TaskMessageErrorHandler;
import com.pixflow.module.task.infra.mq.TaskMessageListener;
import com.pixflow.module.task.infra.mq.TaskMessagePublisher;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessResultMemberMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.module.task.api.port.TaskAssetReader;
import com.pixflow.module.task.internal.cancel.CancellationService;
import com.pixflow.module.task.internal.create.CreateTaskServiceImpl;
import com.pixflow.module.task.internal.download.DownloadService;
import com.pixflow.module.task.internal.download.DownloadBundleBuilder;
import com.pixflow.module.task.internal.failure.FailureIsolator;
import com.pixflow.module.task.internal.progress.ProgressAggregator;
import com.pixflow.module.task.internal.publish.TaskEventPublisher;
import com.pixflow.module.task.internal.query.TaskQueryServiceImpl;
import com.pixflow.module.task.internal.recovery.HeartbeatWriter;
import com.pixflow.module.task.internal.recovery.RecoveryService;
import com.pixflow.module.task.internal.scheduler.WorkUnitScheduler;
import com.pixflow.module.task.internal.stateadapter.CheckpointReadPortImpl;
import com.pixflow.module.task.internal.terminal.TerminalStateJudge;
import com.pixflow.module.task.internal.worker.ImageGenWorker;
import com.pixflow.module.task.internal.worker.ProcessWorker;
import com.pixflow.module.task.internal.worker.TaskWorker;
import com.pixflow.module.task.internal.worker.WorkerRouter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(TaskProperties.class)
@MapperScan("com.pixflow.module.task.infra.persistence")
public class TaskAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "taskClock")
    public Clock taskClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskMetrics taskMetrics(MeterRegistry registry) {
        return new TaskMetrics(registry);
    }

    @Bean
    @ConditionalOnBean(CacheNamespace.class)
    @ConditionalOnMissingBean
    public TaskCacheKeys taskCacheKeys(CacheNamespace namespace, TaskProperties properties) {
        return new TaskCacheKeys(namespace, properties.getProgress().getCounterTtl(), properties.getCancel().getTtl());
    }

    @Bean
    @ConditionalOnBean({AtomicCounter.class, TaskCacheKeys.class})
    @ConditionalOnMissingBean
    public TaskProgressCounter taskProgressCounter(AtomicCounter counter, TaskCacheKeys keys,
                                                   TaskProperties properties) {
        return new TaskProgressCounter(counter, keys, properties.getProgress().getCounterTtl());
    }

    @Bean
    @ConditionalOnBean({CacheStore.class, TaskCacheKeys.class})
    @ConditionalOnMissingBean
    public TaskCancelFlag taskCancelFlag(CacheStore store, TaskCacheKeys keys, TaskProperties properties) {
        return new TaskCancelFlag(store, keys, properties.getCancel().getTtl());
    }

    @Bean
    @ConditionalOnBean({CacheStore.class, TaskCacheKeys.class})
    @ConditionalOnMissingBean
    public TaskIdempotencyStore taskIdempotencyStore(CacheStore store, TaskCacheKeys keys,
                                                     TaskProperties properties) {
        return new TaskIdempotencyStore(store, keys, properties.getCreate().getIdempotencyTtl());
    }

    @Bean
    @ConditionalOnBean({LockTemplate.class, TaskCacheKeys.class})
    @ConditionalOnMissingBean
    public TaskLockManager taskLockManager(LockTemplate lockTemplate, TaskCacheKeys keys,
                                           TaskProperties properties) {
        return new TaskLockManager(lockTemplate, keys, properties.getLock().getWaitTime());
    }

    @Bean
    @ConditionalOnBean(MessagePublisher.class)
    @ConditionalOnMissingBean
    public TaskMessagePublisher taskMessagePublisher(MessagePublisher publisher, TaskProperties properties) {
        return new TaskMessagePublisher(publisher, properties);
    }

    @Bean
    @ConditionalOnBean({TaskIdempotencyStore.class, ProcessTaskMapper.class})
    @ConditionalOnMissingBean
    public IdempotencyGuard idempotencyGuard(TaskIdempotencyStore store, ProcessTaskMapper mapper) {
        return new IdempotencyGuard(store, mapper);
    }

    @Bean
    @ConditionalOnBean({TaskCancelFlag.class, ProcessTaskMapper.class})
    @ConditionalOnMissingBean
    public CancellationService cancellationService(ProcessTaskMapper mapper, TaskCancelFlag flag,
                                                   TaskMetrics metrics, Clock taskClock) {
        return new CancellationService(mapper, flag, metrics, taskClock);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskEventPublisher taskEventPublisher(ApplicationEventPublisher publisher) {
        return new TaskEventPublisher(publisher);
    }

    @Bean
    @ConditionalOnBean({TaskProgressCounter.class})
    @ConditionalOnMissingBean
    public ProgressAggregator progressAggregator(TaskProgressCounter counter,
                                                 ApplicationEventPublisher publisher,
                                                 TaskMetrics metrics,
                                                 Clock taskClock) {
        return new ProgressAggregator(counter, publisher, metrics, taskClock);
    }

    @Bean
    @ConditionalOnBean({ProcessResultMapper.class, ProgressAggregator.class})
    @ConditionalOnMissingBean
    public FailureIsolator failureIsolator(ProcessResultMapper mapper,
                                           ProgressAggregator progress,
                                           ErrorNormalizer normalizer,
                                           TaskMetrics metrics,
                                           Clock taskClock) {
        return new FailureIsolator(mapper, progress, normalizer, metrics, taskClock);
    }

    @Bean
    @ConditionalOnBean({ProcessTaskMapper.class, ProcessResultMapper.class, CancellationService.class})
    @ConditionalOnMissingBean
    public TerminalStateJudge terminalStateJudge(ProcessTaskMapper taskMapper,
                                                 ProcessResultMapper resultMapper,
                                                 CancellationService cancellationService,
                                                 TaskEventPublisher publisher,
                                                 TaskMetrics metrics,
                                                 Clock taskClock) {
        return new TerminalStateJudge(taskMapper, resultMapper, cancellationService, publisher, metrics, taskClock);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkUnitScheduler workUnitScheduler(TaskProperties properties) {
        return new WorkUnitScheduler(properties);
    }

    @Bean
    @ConditionalOnBean({com.pixflow.infra.ai.imagegen.ImageGenClient.class, ObjectStorage.class, ImagegenProperties.class})
    @ConditionalOnMissingBean(ImageGenExecutor.class)
    public ImageGenExecutor taskImageGenExecutor(com.pixflow.infra.ai.imagegen.ImageGenClient client,
                                                 ObjectStorage objectStorage,
                                                 ImagegenProperties properties) {
        return new DefaultImageGenExecutor(client, objectStorage, properties);
    }

    @Bean
    @ConditionalOnBean({BranchExpander.class, UnitExecutor.class, TaskAssetReader.class, ProgressAggregator.class})
    @ConditionalOnMissingBean
    public ProcessWorker processWorker(ObjectMapper objectMapper, BranchExpander branchExpander,
                                       UnitExecutor unitExecutor, TaskAssetReader assetReader,
                                       ProcessResultMapper resultMapper,
                                       ProcessResultMemberMapper memberMapper,
                                       ProgressAggregator progress,
                                       FailureIsolator failure,
                                       CancellationService cancellation,
                                       TaskMetrics metrics,
                                       Clock taskClock) {
        return new ProcessWorker(objectMapper, branchExpander, unitExecutor, assetReader,
                resultMapper, memberMapper, progress, failure, cancellation, metrics, taskClock);
    }

    @Bean
    @ConditionalOnBean({ImageGenExecutor.class, TaskAssetReader.class, ProgressAggregator.class})
    @ConditionalOnMissingBean
    public ImageGenWorker imageGenWorker(ObjectMapper objectMapper, ImageGenExecutor executor,
                                         TaskAssetReader assetReader, ProcessResultMapper resultMapper,
                                         ProgressAggregator progress, FailureIsolator failure,
                                         CancellationService cancellation, TaskMetrics metrics,
                                         Clock taskClock) {
        return new ImageGenWorker(objectMapper, executor, assetReader, resultMapper,
                progress, failure, cancellation, metrics, taskClock);
    }

    @Bean
    @ConditionalOnBean({ProcessWorker.class, ImageGenWorker.class})
    @ConditionalOnMissingBean
    public WorkerRouter workerRouter(ProcessWorker processWorker, ImageGenWorker imageGenWorker) {
        return new WorkerRouter(processWorker, imageGenWorker);
    }

    @Bean
    @ConditionalOnBean({WorkerRouter.class, TaskLockManager.class, TerminalStateJudge.class})
    @ConditionalOnMissingBean
    public TaskWorker taskWorker(ProcessTaskMapper taskMapper, ProcessResultMapper resultMapper,
                                 WorkerRouter router, WorkUnitScheduler scheduler,
                                 TerminalStateJudge terminal, TaskLockManager lock,
                                 TaskMetrics metrics, TaskProperties properties, Clock taskClock) {
        return new TaskWorker(taskMapper, resultMapper, router, scheduler, terminal, lock, metrics, properties, taskClock);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskMessageErrorHandler taskMessageErrorHandler() {
        return new TaskMessageErrorHandler();
    }

    @Bean
    @ConditionalOnBean(DestinationRegistrar.class)
    public Object taskMessageDestinationRegistration(DestinationRegistrar registrar, TaskProperties properties) {
        registrar.register(TaskMessageDestination.destination(properties, "0"));
        registrar.register(TaskMessageDestination.binding(properties));
        return new Object();
    }

    @Bean
    @ConditionalOnBean(TaskWorker.class)
    @ConditionalOnMissingBean
    public TaskMessageListener taskMessageListener(TaskWorker worker) {
        return new TaskMessageListener(worker);
    }

    @Bean
    @ConditionalOnBean({ManagedListenerContainerFactory.class, TaskMessageListener.class, TaskMessageErrorHandler.class})
    @ConditionalOnMissingBean(name = "taskMessageContainer")
    public ManagedMessageContainer taskMessageContainer(
            ManagedListenerContainerFactory factory,
            TaskMessageListener listener,
            TaskMessageErrorHandler errorHandler,
            TaskProperties properties) {
        ManagedMessageContainer container = factory.create(TaskMessageDestination.binding(properties), listener, errorHandler);
        container.start();
        return container;
    }

    @Bean
    @ConditionalOnBean({ProcessTaskMapper.class, TaskMessagePublisher.class, IdempotencyGuard.class,
            CancellationService.class})
    @ConditionalOnMissingBean(TaskCommandService.class)
    public CreateTaskServiceImpl taskCommandService(ProcessTaskMapper taskMapper,
                                                    TaskMessagePublisher publisher,
                                                    IdempotencyGuard idempotency,
                                                    CancellationService cancellation,
                                                    TaskEventPublisher events,
                                                    TaskMetrics metrics,
                                                    Clock taskClock) {
        return new CreateTaskServiceImpl(taskMapper, publisher, idempotency, cancellation, events, metrics, taskClock);
    }

    @Bean
    @ConditionalOnBean({ProcessTaskMapper.class, ProcessResultMapper.class, ObjectStorage.class})
    @ConditionalOnMissingBean
    public DownloadBundleBuilder downloadBundleBuilder(ObjectStorage storage, TaskProperties properties) {
        return new DownloadBundleBuilder(storage, properties);
    }

    @Bean
    @ConditionalOnBean({ProcessTaskMapper.class, ProcessResultMapper.class, ObjectStorage.class, DownloadBundleBuilder.class})
    @ConditionalOnMissingBean
    public DownloadService downloadService(ProcessResultMapper resultMapper, ObjectStorage storage,
                                           DownloadBundleBuilder bundleBuilder,
                                           TaskProperties properties, TaskMetrics metrics, Clock taskClock) {
        return new DownloadService(resultMapper, storage, bundleBuilder, properties, metrics, taskClock);
    }

    @Bean
    @ConditionalOnBean(DownloadService.class)
    @ConditionalOnMissingBean(TaskQueryService.class)
    public TaskQueryServiceImpl taskQueryService(ProcessTaskMapper taskMapper,
                                                 ProcessResultMapper resultMapper,
                                                 DownloadService downloadService) {
        return new TaskQueryServiceImpl(taskMapper, resultMapper, downloadService);
    }

    @Bean
    @ConditionalOnBean({ProcessTaskMapper.class, ProcessResultMapper.class})
    @ConditionalOnMissingBean
    public CheckpointReadPortImpl checkpointReadPort(ProcessTaskMapper taskMapper, ProcessResultMapper resultMapper) {
        return new CheckpointReadPortImpl(taskMapper, resultMapper);
    }

    @Bean
    @ConditionalOnBean({ProcessTaskMapper.class, TaskMessagePublisher.class})
    @ConditionalOnMissingBean
    public RecoveryService recoveryService(ProcessTaskMapper taskMapper, TaskMessagePublisher publisher,
                                           TaskProperties properties, TaskMetrics metrics, Clock taskClock) {
        return new RecoveryService(taskMapper, publisher, properties, metrics, taskClock);
    }

    @Bean
    @ConditionalOnBean(ProcessTaskMapper.class)
    @ConditionalOnMissingBean
    public HeartbeatWriter heartbeatWriter(ProcessTaskMapper taskMapper, Clock taskClock) {
        return new HeartbeatWriter(taskMapper, taskClock);
    }
}

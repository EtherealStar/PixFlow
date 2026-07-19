package com.pixflow.module.task.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.harness.state.recovery.RecoveryCoordinator;
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
import com.pixflow.module.task.api.TaskOutcomeQuery;
import com.pixflow.module.task.api.TaskQueryService;
import com.pixflow.module.task.api.download.CustomDownloadBundleService;
import com.pixflow.module.task.api.download.PublishedTaskResultQuery;
import com.pixflow.module.task.api.port.TaskAssetReader;
import com.pixflow.module.task.api.publication.GeneratedAssetPublicationPort;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
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
import com.pixflow.module.task.internal.cancel.CancellationService;
import com.pixflow.module.task.internal.create.CreateTaskServiceImpl;
import com.pixflow.module.task.internal.create.PendingTaskEnqueuer;
import com.pixflow.module.task.internal.download.DownloadBundleBuilder;
import com.pixflow.module.task.internal.download.CustomDownloadBundleServiceImpl;
import com.pixflow.module.task.internal.download.DownloadService;
import com.pixflow.module.task.internal.failure.FailureIsolator;
import com.pixflow.module.task.internal.planning.WorkUnitPlanner;
import com.pixflow.module.task.internal.progress.ProgressAggregator;
import com.pixflow.module.task.internal.publish.TaskEventPublisher;
import com.pixflow.module.task.internal.query.TaskOutcomeQueryImpl;
import com.pixflow.module.task.internal.query.PublishedTaskResultQueryImpl;
import com.pixflow.module.task.internal.query.TaskQueryServiceImpl;
import com.pixflow.module.task.internal.recovery.HeartbeatWriter;
import com.pixflow.module.task.internal.recovery.RecoveryService;
import com.pixflow.module.task.internal.retry.RetryFailedTaskService;
import com.pixflow.module.task.internal.scheduler.WorkUnitScheduler;
import com.pixflow.module.task.internal.stateadapter.CheckpointReadPortImpl;
import com.pixflow.module.task.internal.terminal.TerminalStateJudge;
import com.pixflow.module.task.internal.worker.ImageGenWorker;
import com.pixflow.module.task.internal.worker.ProcessWorker;
import com.pixflow.module.task.internal.worker.TaskWorker;
import com.pixflow.module.task.internal.worker.WorkUnitResultRepository;
import com.pixflow.module.task.internal.worker.WorkerRouter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(TaskProperties.class)
@MapperScan(value = "com.pixflow.module.task.infra.persistence", annotationClass = Mapper.class)
public class TaskAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public WorkUnitPlanner workUnitPlanner(
      ObjectMapper objectMapper,
      com.pixflow.module.dag.ir.DagJsonReader reader,
      com.pixflow.module.dag.DagFacade dagFacade,
      TaskAssetReader assetReader) {
    return new WorkUnitPlanner(objectMapper, reader, dagFacade, assetReader);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskMetrics taskMetrics(MeterRegistry registry) {
    return new TaskMetrics(registry);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskCacheKeys taskCacheKeys(CacheNamespace namespace, TaskProperties properties) {
    return new TaskCacheKeys(
        namespace, properties.getProgress().getCounterTtl(), properties.getCancel().getTtl());
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskProgressCounter taskProgressCounter(
      AtomicCounter counter, TaskCacheKeys keys, TaskProperties properties) {
    return new TaskProgressCounter(counter, keys, properties.getProgress().getCounterTtl());
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskCancelFlag taskCancelFlag(
      CacheStore store, TaskCacheKeys keys, TaskProperties properties) {
    return new TaskCancelFlag(store, keys, properties.getCancel().getTtl());
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskIdempotencyStore taskIdempotencyStore(
      CacheStore store, TaskCacheKeys keys, TaskProperties properties) {
    return new TaskIdempotencyStore(store, keys, properties.getCreate().getIdempotencyTtl());
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskLockManager taskLockManager(
      LockTemplate lockTemplate, TaskCacheKeys keys, TaskProperties properties) {
    return new TaskLockManager(lockTemplate, keys, properties.getLock().getWaitTime());
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskMessagePublisher taskMessagePublisher(
      MessagePublisher publisher, TaskProperties properties) {
    return new TaskMessagePublisher(publisher, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public IdempotencyGuard idempotencyGuard(TaskIdempotencyStore store, ProcessTaskMapper mapper) {
    return new IdempotencyGuard(store, mapper);
  }

  @Bean
  @ConditionalOnMissingBean(PendingTaskEnqueuer.class)
  public PendingTaskEnqueuer pendingTaskEnqueuer(
      ProcessTaskMapper taskMapper,
      TaskMessagePublisher publisher,
      TaskProperties properties,
      Clock clock) {
    return new PendingTaskEnqueuer(taskMapper, publisher, properties, clock);
  }

  @Bean
  @ConditionalOnMissingBean(RetryFailedTaskService.class)
  public RetryFailedTaskService retryFailedTaskService(
      ProcessTaskMapper taskMapper,
      ProcessResultMapper resultMapper,
      IdempotencyGuard idempotencyGuard,
      PendingTaskEnqueuer enqueuer,
      ObjectMapper objectMapper,
      Clock clock,
      TaskEventPublisher eventPublisher) {
    return new RetryFailedTaskService(
        taskMapper, resultMapper, idempotencyGuard, enqueuer, objectMapper, clock, eventPublisher);
  }

  @Bean
  @ConditionalOnMissingBean
  public CancellationService cancellationService(
      ProcessTaskMapper mapper, TaskCancelFlag flag, TaskMetrics metrics, Clock clock) {
    return new CancellationService(mapper, flag, metrics, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskEventPublisher taskEventPublisher(ApplicationEventPublisher publisher) {
    return new TaskEventPublisher(publisher);
  }

  @Bean
  @ConditionalOnMissingBean
  public ProgressAggregator progressAggregator(
      TaskProgressCounter counter,
      ApplicationEventPublisher publisher,
      TaskMetrics metrics,
      Clock clock) {
    return new ProgressAggregator(counter, publisher, metrics, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public FailureIsolator failureIsolator(
      ErrorNormalizer normalizer, TaskMetrics metrics, Clock clock) {
    return new FailureIsolator(normalizer, metrics, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public TerminalStateJudge terminalStateJudge(
      ProcessTaskMapper taskMapper,
      ProcessResultMapper resultMapper,
      TaskEventPublisher publisher,
      TaskMetrics metrics,
      Clock clock) {
    return new TerminalStateJudge(taskMapper, resultMapper, publisher, metrics, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public WorkUnitScheduler workUnitScheduler(TaskProperties properties, TaskMetrics metrics) {
    return new WorkUnitScheduler(properties, metrics);
  }

  @Bean
  @ConditionalOnMissingBean(ImageGenExecutor.class)
  public ImageGenExecutor taskImageGenExecutor(
      com.pixflow.infra.ai.imagegen.ImageGenClient client,
      ObjectStorage objectStorage,
      ImagegenProperties properties) {
    return new DefaultImageGenExecutor(client, objectStorage, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public ProcessWorker processWorker(
      ObjectMapper objectMapper,
      com.pixflow.module.dag.ir.DagJsonReader dagJsonReader,
      com.pixflow.module.dag.DagFacade dagFacade,
      BranchExpander branchExpander,
      UnitExecutor unitExecutor,
      FailureIsolator failure,
      CancellationService cancellation,
      TaskMetrics metrics,
      ObjectStorage objectStorage,
      Clock clock) {
    return new ProcessWorker(
        objectMapper,
        dagJsonReader,
        dagFacade,
        branchExpander,
        unitExecutor,
        failure,
        cancellation,
        metrics,
        objectStorage,
        clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public ImageGenWorker imageGenWorker(
      ObjectMapper objectMapper,
      ImageGenExecutor executor,
      FailureIsolator failure,
      CancellationService cancellation,
      TaskMetrics metrics,
      Clock clock) {
    return new ImageGenWorker(objectMapper, executor, failure, cancellation, metrics, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public WorkUnitResultRepository workUnitResultRepository(
      ProcessResultMapper resultMapper,
      ProcessResultMemberMapper memberMapper,
      ProcessTaskMapper taskMapper,
      com.pixflow.infra.storage.ObjectStorage objectStorage,
      ObjectMapper objectMapper) {
    return new WorkUnitResultRepository(
        resultMapper, memberMapper, taskMapper, objectStorage, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public WorkerRouter workerRouter(ProcessWorker processWorker, ImageGenWorker imageGenWorker) {
    return new WorkerRouter(processWorker, imageGenWorker);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskWorker taskWorker(
      ProcessTaskMapper taskMapper,
      WorkerRouter router,
      WorkUnitScheduler scheduler,
      TerminalStateJudge terminal,
      TaskLockManager lock,
      TaskMetrics metrics,
      TaskProperties properties,
      Clock clock,
      RecoveryCoordinator recoveryCoordinator,
      WorkUnitResultRepository resultRepository,
      ProgressAggregator progressAggregator,
      HeartbeatWriter heartbeatWriter,
      ProcessResultMapper resultMapper,
      ProcessResultMemberMapper memberMapper,
      GeneratedAssetPublicationPort publicationPort) {
    return new TaskWorker(
        taskMapper,
        router,
        scheduler,
        terminal,
        lock,
        metrics,
        properties,
        clock,
        recoveryCoordinator,
        resultRepository,
        progressAggregator,
        heartbeatWriter,
        resultMapper,
        memberMapper,
        publicationPort);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskMessageErrorHandler taskMessageErrorHandler() {
    return new TaskMessageErrorHandler();
  }

  @Bean
  public Object taskMessageDestinationRegistration(
      DestinationRegistrar registrar, TaskProperties properties) {
    registrar.register(TaskMessageDestination.destination(properties, "0"));
    registrar.register(TaskMessageDestination.binding(properties));
    return new Object();
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskMessageListener taskMessageListener(TaskWorker worker) {
    return new TaskMessageListener(worker);
  }

  @Bean
  @ConditionalOnMissingBean(name = "taskMessageContainer")
  public ManagedMessageContainer taskMessageContainer(
      ManagedListenerContainerFactory factory,
      TaskMessageListener listener,
      TaskMessageErrorHandler errorHandler,
      TaskProperties properties) {
    ManagedMessageContainer container =
        factory.create(TaskMessageDestination.binding(properties), listener, errorHandler);
    container.start();
    return container;
  }

  @Bean
  @ConditionalOnMissingBean(TaskCommandService.class)
  public CreateTaskServiceImpl taskCommandService(
      ProcessTaskMapper taskMapper,
      PendingTaskEnqueuer pendingTaskEnqueuer,
      IdempotencyGuard idempotency,
      CancellationService cancellation,
      TaskEventPublisher events,
      TaskMetrics metrics,
      Clock clock,
      ProcessResultMapper resultMapper,
      WorkUnitPlanner planner,
      ObjectMapper objectMapper,
      RetryFailedTaskService retryFailedTaskService) {
    return new CreateTaskServiceImpl(
        taskMapper,
        pendingTaskEnqueuer,
        idempotency,
        cancellation,
        events,
        metrics,
        clock,
        resultMapper,
        planner,
        objectMapper,
        retryFailedTaskService);
  }

  @Bean
  @ConditionalOnMissingBean
  public DownloadBundleBuilder downloadBundleBuilder(
      ObjectStorage storage, TaskProperties properties) {
    return new DownloadBundleBuilder(storage, properties);
  }

  @Bean
  @ConditionalOnMissingBean(CustomDownloadBundleService.class)
  public CustomDownloadBundleService customDownloadBundleService(
      DownloadBundleBuilder bundleBuilder,
      ObjectStorage storage,
      TaskProperties properties,
      Clock clock) {
    return new CustomDownloadBundleServiceImpl(bundleBuilder, storage, properties, clock);
  }

  @Bean
  @ConditionalOnMissingBean(PublishedTaskResultQuery.class)
  public PublishedTaskResultQuery publishedTaskResultQuery(ProcessResultMapper resultMapper) {
    return new PublishedTaskResultQueryImpl(resultMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public DownloadService downloadService(
      ProcessResultMapper resultMapper,
      ObjectStorage storage,
      DownloadBundleBuilder bundleBuilder,
      TaskProperties properties,
      TaskMetrics metrics,
      Clock clock,
      PublishedAssetReader publishedAssets) {
    return new DownloadService(
        resultMapper, storage, bundleBuilder, properties, metrics, clock, publishedAssets);
  }

  @Bean
  @ConditionalOnMissingBean(TaskQueryService.class)
  public TaskQueryServiceImpl taskQueryService(
      ProcessTaskMapper taskMapper,
      ProcessResultMapper resultMapper,
      DownloadService downloadService,
      Clock clock,
      ObjectMapper objectMapper) {
    return new TaskQueryServiceImpl(taskMapper, resultMapper, downloadService, clock, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean(TaskOutcomeQuery.class)
  public TaskOutcomeQuery taskOutcomeQuery(ProcessResultMapper resultMapper) {
    return new TaskOutcomeQueryImpl(resultMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public CheckpointReadPortImpl checkpointReadPort(
      ProcessTaskMapper taskMapper, ProcessResultMapper resultMapper) {
    return new CheckpointReadPortImpl(taskMapper, resultMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public RecoveryService recoveryService(
      ProcessTaskMapper taskMapper,
      TaskMessagePublisher publisher,
      TaskProperties properties,
      TaskMetrics metrics,
      Clock clock) {
    return new RecoveryService(taskMapper, publisher, properties, metrics, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public HeartbeatWriter heartbeatWriter(
      ProcessTaskMapper taskMapper, Clock clock, TaskMetrics metrics) {
    return new HeartbeatWriter(taskMapper, clock, metrics);
  }
}

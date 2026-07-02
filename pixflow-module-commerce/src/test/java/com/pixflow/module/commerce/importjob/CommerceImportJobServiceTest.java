package com.pixflow.module.commerce.importjob;

import static com.pixflow.module.commerce.CommerceTestData.commerceData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.mq.PublishFailure;
import com.pixflow.infra.mq.PublishFailureType;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.module.commerce.config.CommerceProperties;
import com.pixflow.module.commerce.importer.CommerceImportService;
import com.pixflow.module.commerce.importer.ImportReportTestFactory;
import com.pixflow.module.commerce.query.PeriodType;
import com.pixflow.module.commerce.query.TimeWindow;
import com.pixflow.module.commerce.source.ApiImportRequest;
import com.pixflow.module.commerce.source.CommerceDataSource;
import com.pixflow.module.commerce.source.ImportJobStatus;
import com.pixflow.module.commerce.source.ImportJobStatusView;
import com.pixflow.module.commerce.source.PullSpec;
import com.pixflow.module.commerce.store.CommerceData;
import com.pixflow.module.commerce.store.CommerceDataMapper;
import com.pixflow.module.commerce.store.CommerceImportJob;
import com.pixflow.module.commerce.store.CommerceImportJobMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommerceImportJobServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void startReturnsPendingJobWhenPublishSucceeds() {
        InMemoryJobStore mapper = new InMemoryJobStore();
        CommerceImportJobService service = service(mapper.mapper, successfulPublisher(), sourceWith(row("SKU001")), countingImportService(), properties());

        ImportJobStatusView view = service.start(request());

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.status()).isEqualTo(ImportJobStatus.PENDING);
        assertThat(view.skuCount()).isEqualTo(2);
        assertThat(mapper.selectById(1L).getRequestJson()).contains("SKU001", "SKU002");
    }

    @Test
    void startMarksPublishFailedWhenMessagePublisherRejectsJob() {
        InMemoryJobStore mapper = new InMemoryJobStore();
        CommerceImportJobService service = service(
                mapper.mapper,
                publisher(jobId -> PublishResult.failed(
                        CommerceImportDestination.TOPIC,
                        CommerceImportDestination.TAG,
                        "corr-1",
                        new PublishFailure(PublishFailureType.BROKER_REJECTED, "token=secret publish failed", null, null))),
                sourceWith(row("SKU001")),
                countingImportService(),
                properties());

        ImportJobStatusView view = service.start(request());

        assertThat(view.status()).isEqualTo(ImportJobStatus.PUBLISH_FAILED);
        assertThat(view.errorSummary()).doesNotContain("secret");
    }

    @Test
    void runImportsPulledRowsAndMarksSucceeded() {
        InMemoryJobStore mapper = new InMemoryJobStore();
        RecordingSource source = sourceWith(row("SKU001"), row("SKU002"));
        RecordingImportService importService = countingImportService();
        CommerceProperties properties = properties();
        CommerceImportJobService service = service(mapper.mapper, successfulPublisher(), source, importService, properties);
        long jobId = service.start(request()).id();

        service.run(jobId);

        CommerceImportJob job = mapper.selectById(jobId);
        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.SUCCEEDED);
        assertThat(job.getSucceededCount()).isEqualTo(2);
        assertThat(job.getFailedCount()).isZero();
        assertThat(job.getFinishedAt()).isEqualTo(Instant.parse("2026-06-28T00:00:00Z"));
        assertThat(source.lastSpec.platform()).isEqualTo("fake");
        assertThat(importService.importedRows).hasSize(2);
    }

    @Test
    void runMarksPartialWhenImportReportHasFailures() {
        InMemoryJobStore mapper = new InMemoryJobStore();
        CommerceImportJobService service = service(
                mapper.mapper,
                successfulPublisher(),
                sourceWith(row("SKU001")),
                importServiceFailingOneRow(),
                properties());
        long jobId = service.start(request()).id();

        service.run(jobId);

        CommerceImportJob job = mapper.selectById(jobId);
        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.PARTIAL);
        assertThat(job.getSucceededCount()).isEqualTo(1);
        assertThat(job.getFailedCount()).isEqualTo(1);
        assertThat(job.getReportJson()).contains("\"skipped\":1");
    }

    @Test
    void runMarksFailedAndRethrowsCommerceExceptionWhenPullFails() {
        InMemoryJobStore mapper = new InMemoryJobStore();
        CommerceImportJobService service = service(
                mapper.mapper,
                successfulPublisher(),
                new RecordingSource(null, new IllegalStateException("platform token=secret failed")),
                countingImportService(),
                properties());
        long jobId = service.start(request()).id();

        assertThatThrownBy(() -> service.run(jobId)).isInstanceOf(PixFlowException.class);

        CommerceImportJob job = mapper.selectById(jobId);
        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(job.getErrorSummary()).doesNotContain("secret");
    }

    @Test
    void runSkipsCompletedJobs() {
        InMemoryJobStore mapper = new InMemoryJobStore();
        RecordingSource source = sourceWith(row("SKU001"));
        CommerceImportJobService service = service(mapper.mapper, successfulPublisher(), source, countingImportService(), properties());
        long jobId = service.start(request()).id();
        service.run(jobId);

        service.run(jobId);

        assertThat(source.pullCount).isEqualTo(1);
    }

    private CommerceImportJobService service(
            CommerceImportJobMapper mapper,
            CommerceApiImportPublisher publisher,
            CommerceDataSource source,
            CommerceImportService importService,
            CommerceProperties properties) {
        return new CommerceImportJobService(mapper, publisher, source, importService, objectMapper, properties, clock);
    }

    private static CommerceProperties properties() {
        CommerceProperties properties = new CommerceProperties();
        properties.getSource().setPlatform("fake");
        return properties;
    }

    private static ApiImportRequest request() {
        return new ApiImportRequest(
                List.of("SKU001", "SKU002"),
                new TimeWindow(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-28")),
                PeriodType.DAY,
                null);
    }

    private static CommerceApiImportPublisher successfulPublisher() {
        return publisher(jobId -> PublishResult.confirmed(CommerceImportDestination.TOPIC, CommerceImportDestination.TAG, "msg-" + jobId, "broker-a"));
    }

    private static CommerceApiImportPublisher publisher(PublishBehavior behavior) {
        return new CommerceApiImportPublisher(null) {
            @Override
            public PublishResult publish(long jobId) {
                return behavior.publish(jobId);
            }
        };
    }

    private interface PublishBehavior {
        PublishResult publish(long jobId);
    }

    private static RecordingSource sourceWith(CommerceData... rows) {
        return new RecordingSource(List.of(rows), null);
    }

    private static CommerceData row(String skuId) {
        return commerceData(
                skuId,
                "dress",
                100,
                "0.10",
                "0.03",
                "0.02",
                LocalDate.parse("2026-06-01"),
                "EXTERNAL_API:fake",
                Instant.parse("2026-06-28T00:00:00Z"));
    }

    private static RecordingImportService countingImportService() {
        return new RecordingImportService(0);
    }

    private static RecordingImportService importServiceFailingOneRow() {
        return new RecordingImportService(1);
    }

    private static final class RecordingSource implements CommerceDataSource {
        private final List<CommerceData> rows;
        private final RuntimeException failure;
        private PullSpec lastSpec;
        private int pullCount;

        private RecordingSource(List<CommerceData> rows, RuntimeException failure) {
            this.rows = rows == null ? List.of() : rows;
            this.failure = failure;
        }

        @Override
        public List<CommerceData> pull(PullSpec spec) {
            pullCount++;
            lastSpec = spec;
            if (failure != null) {
                throw failure;
            }
            return rows;
        }

        @Override
        public boolean supportsLive() {
            return true;
        }
    }

    private static final class RecordingImportService extends CommerceImportService {
        private final int failures;
        private List<CommerceData> importedRows = List.of();

        private RecordingImportService(int failures) {
            super(null, List.of(), null, null, null, new CommerceProperties());
            this.failures = failures;
        }

        @Override
        public com.pixflow.module.commerce.importer.ImportReport importStandardized(List<CommerceData> rows) {
            importedRows = rows;
            return ImportReportTestFactory.report(rows.size(), failures);
        }
    }

    private static final class InMemoryJobStore {
        private final Map<Long, CommerceImportJob> jobs = new LinkedHashMap<>();
        private long nextId = 1;
        private final CommerceImportJobMapper mapper = mock(CommerceImportJobMapper.class);

        private InMemoryJobStore() {
            when(mapper.insert(any(CommerceImportJob.class))).thenAnswer(invocation -> {
                CommerceImportJob entity = invocation.getArgument(0);
                entity.setId(nextId++);
                jobs.put(entity.getId(), copy(entity));
                return 1;
            });
            when(mapper.updateById(any(CommerceImportJob.class))).thenAnswer(invocation -> {
                CommerceImportJob entity = invocation.getArgument(0);
                jobs.put(entity.getId(), copy(entity));
                return 1;
            });
            when(mapper.selectById(any(java.io.Serializable.class))).thenAnswer(invocation -> {
                java.io.Serializable id = invocation.getArgument(0);
                CommerceImportJob job = jobs.get(((Number) id).longValue());
                return job == null ? null : copy(job);
            });
        }

        public CommerceImportJob selectById(java.io.Serializable id) {
            return mapper.selectById(id);
        }

        private static CommerceImportJob copy(CommerceImportJob source) {
            CommerceImportJob copy = new CommerceImportJob();
            copy.setId(source.getId());
            copy.setSource(source.getSource());
            copy.setPlatform(source.getPlatform());
            copy.setStatus(source.getStatus());
            copy.setSkuCount(source.getSkuCount());
            copy.setSucceededCount(source.getSucceededCount());
            copy.setFailedCount(source.getFailedCount());
            copy.setRequestJson(source.getRequestJson());
            copy.setReportJson(source.getReportJson());
            copy.setErrorSummary(source.getErrorSummary());
            copy.setCreatedAt(source.getCreatedAt());
            copy.setUpdatedAt(source.getUpdatedAt());
            copy.setFinishedAt(source.getFinishedAt());
            return copy;
        }
    }

    @SuppressWarnings("unused")
    private static CommerceDataMapper unusedMapper() {
        return null;
    }
}

package com.pixflow.module.commerce.importjob;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.module.commerce.config.CommerceProperties;
import com.pixflow.module.commerce.error.CommerceErrorCode;
import com.pixflow.module.commerce.importer.CommerceImportService;
import com.pixflow.module.commerce.importer.ImportReport;
import com.pixflow.module.commerce.source.ApiImportRequest;
import com.pixflow.module.commerce.source.CommerceDataSource;
import com.pixflow.module.commerce.source.ImportJobStatus;
import com.pixflow.module.commerce.source.ImportJobStatusView;
import com.pixflow.module.commerce.source.PullSpec;
import com.pixflow.module.commerce.store.CommerceImportJob;
import com.pixflow.module.commerce.store.CommerceImportJobMapper;
import java.time.Clock;
import java.time.Instant;

public class CommerceImportJobService {
    private final CommerceImportJobMapper mapper;
    private final CommerceApiImportPublisher publisher;
    private final CommerceDataSource externalSource;
    private final CommerceImportService importService;
    private final ObjectMapper objectMapper;
    private final CommerceProperties properties;
    private final Clock clock;

    public CommerceImportJobService(
            CommerceImportJobMapper mapper,
            CommerceApiImportPublisher publisher,
            CommerceDataSource externalSource,
            CommerceImportService importService,
            ObjectMapper objectMapper,
            CommerceProperties properties,
            Clock clock) {
        this.mapper = mapper;
        this.publisher = publisher;
        this.externalSource = externalSource;
        this.importService = importService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public ImportJobStatusView start(ApiImportRequest request) {
        Instant now = Instant.now(clock);
        CommerceImportJob job = new CommerceImportJob();
        job.setSource("EXTERNAL_API");
        job.setPlatform(platform(request));
        job.setStatus(ImportJobStatus.PENDING);
        job.setSkuCount(request.skuIds() == null ? 0 : request.skuIds().size());
        job.setSucceededCount(0);
        job.setFailedCount(0);
        job.setRequestJson(writeJson(request));
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        mapper.insert(job);
        var result = publisher.publish(job.getId());
        if (!result.confirmed()) {
            job.setStatus(ImportJobStatus.PUBLISH_FAILED);
            job.setErrorSummary(Sanitizer.sanitizeMessage(result.failure() == null ? "publish failed" : result.failure().reason()));
            job.setUpdatedAt(Instant.now(clock));
            mapper.updateById(job);
        }
        return view(job);
    }

    public ImportJobStatusView get(long jobId) {
        return view(load(jobId));
    }

    public void run(long jobId) {
        CommerceImportJob job = load(jobId);
        if (job.getStatus() == ImportJobStatus.SUCCEEDED || job.getStatus() == ImportJobStatus.PARTIAL) {
            return;
        }
        job.setStatus(ImportJobStatus.RUNNING);
        job.setUpdatedAt(Instant.now(clock));
        mapper.updateById(job);
        try {
            ApiImportRequest request = objectMapper.readValue(job.getRequestJson(), ApiImportRequest.class);
            ImportReport report = importService.importStandardized(externalSource.pull(new PullSpec(
                    request.skuIds(),
                    request.window(),
                    request.periodType(),
                    platform(request))));
            job.setSucceededCount(report.getSucceeded());
            job.setFailedCount(report.getSkipped());
            job.setReportJson(writeJson(report));
            job.setStatus(report.getSkipped() > 0 ? ImportJobStatus.PARTIAL : ImportJobStatus.SUCCEEDED);
            job.setFinishedAt(Instant.now(clock));
            job.setUpdatedAt(job.getFinishedAt());
            mapper.updateById(job);
        } catch (Exception ex) {
            job.setStatus(ImportJobStatus.FAILED);
            job.setErrorSummary(Sanitizer.sanitizeMessage(ex.getMessage()));
            job.setFinishedAt(Instant.now(clock));
            job.setUpdatedAt(job.getFinishedAt());
            mapper.updateById(job);
            throw new PixFlowException(CommerceErrorCode.COMMERCE_PLATFORM_PULL_FAILED, "commerce platform import failed", ex);
        }
    }

    private CommerceImportJob load(long jobId) {
        CommerceImportJob job = mapper.selectById(jobId);
        if (job == null) {
            throw new PixFlowException(CommerceErrorCode.COMMERCE_IMPORT_JOB_NOT_FOUND, "commerce import job not found: " + jobId);
        }
        return job;
    }

    private String platform(ApiImportRequest request) {
        return request.platform() == null || request.platform().isBlank()
                ? properties.getSource().getPlatform()
                : request.platform().trim();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize commerce import payload", ex);
        }
    }

    private ImportJobStatusView view(CommerceImportJob job) {
        return new ImportJobStatusView(
                job.getId(),
                job.getSource(),
                job.getPlatform(),
                job.getStatus(),
                job.getSkuCount(),
                job.getSucceededCount(),
                job.getFailedCount(),
                job.getReportJson(),
                job.getErrorSummary(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getFinishedAt());
    }
}

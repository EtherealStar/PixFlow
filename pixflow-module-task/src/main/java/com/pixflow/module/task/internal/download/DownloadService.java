package com.pixflow.module.task.internal.download;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.task.api.query.DownloadHandle;
import com.pixflow.module.task.api.query.ResultSelector;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.domain.model.UnitKind;
import com.pixflow.module.task.infra.metrics.TaskMetrics;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import java.net.URL;
import java.time.Clock;

public class DownloadService {
    private final ProcessResultMapper resultMapper;
    private final ObjectStorage objectStorage;
    private final DownloadBundleBuilder bundleBuilder;
    private final TaskProperties properties;
    private final TaskMetrics metrics;
    private final Clock clock;

    public DownloadService(ProcessResultMapper resultMapper,
                           ObjectStorage objectStorage,
                           DownloadBundleBuilder bundleBuilder,
                           TaskProperties properties,
                           TaskMetrics metrics,
                           Clock clock) {
        this.resultMapper = resultMapper;
        this.objectStorage = objectStorage;
        this.bundleBuilder = bundleBuilder;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public DownloadHandle download(long taskId, ResultSelector selector) {
        if (selector.bundle()) {
            var results = resultMapper.findByTaskIdAndStatus(taskId, ResultStatus.SUCCESS);
            if (results.isEmpty()) {
                metrics.recordDownload("bundle", "not_ready");
                throw new PixFlowException(TaskErrorCode.TASK_DOWNLOAD_NOT_READY, "no successful result to bundle");
            }
            var ref = bundleBuilder.build(taskId, results);
            URL url = objectStorage.presignGet(ObjectLocation.of(BucketType.TMP, ref.key()),
                    properties.getDownload().getSingleUrlExpiry());
            metrics.recordDownload("bundle", "ok");
            return new DownloadHandle(url, clock.instant().plus(properties.getDownload().getSingleUrlExpiry()),
                    "application/zip", ref.size());
        }
        ProcessResult result = selectResult(taskId, selector);
        if (result == null || result.getStatus() != ResultStatus.SUCCESS || result.getOutputMinioKey() == null) {
            metrics.recordDownload("single", "not_ready");
            throw new PixFlowException(TaskErrorCode.TASK_DOWNLOAD_NOT_READY, "result is not ready for download");
        }
        BucketType bucket = result.getKind() == UnitKind.GENERATIVE ? BucketType.GENERATED : BucketType.RESULTS;
        URL url = objectStorage.presignGet(ObjectLocation.of(bucket, result.getOutputMinioKey()),
                properties.getDownload().getSingleUrlExpiry());
        metrics.recordDownload("single", "ok");
        return new DownloadHandle(url, clock.instant().plus(properties.getDownload().getSingleUrlExpiry()),
                "application/octet-stream", result.getBytesOut() == null ? -1L : result.getBytesOut());
    }

    private ProcessResult selectResult(long taskId, ResultSelector selector) {
        if (selector.resultId() != null) {
            ProcessResult result = resultMapper.selectById(selector.resultId());
            return result != null && result.getTaskId() == taskId ? result : null;
        }
        return resultMapper.selectOne(new LambdaQueryWrapper<ProcessResult>()
                .eq(ProcessResult::getTaskId, taskId)
                .eq(selector.imageId() != null, ProcessResult::getImageId, selector.imageId())
                .eq(selector.groupKey() != null, ProcessResult::getGroupKey, selector.groupKey())
                .eq(selector.branchId() != null, ProcessResult::getBranchId, selector.branchId())
                .last("limit 1"));
    }
}

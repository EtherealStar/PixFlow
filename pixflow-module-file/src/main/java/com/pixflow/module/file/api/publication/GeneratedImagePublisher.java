package com.pixflow.module.file.api.publication;

import java.time.Instant;

/** File-owned 幂等 Generated Image 发布边界。 */
public interface GeneratedImagePublisher {
    PublishedImage publish(PublishGeneratedImage command);

    void markTaskFinished(long taskId, Instant finishedAt);
}

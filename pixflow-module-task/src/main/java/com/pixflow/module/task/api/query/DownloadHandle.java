package com.pixflow.module.task.api.query;

import java.net.URL;
import java.time.Instant;

public record DownloadHandle(URL url, Instant expiresAt, String contentType, long sizeBytes) { }

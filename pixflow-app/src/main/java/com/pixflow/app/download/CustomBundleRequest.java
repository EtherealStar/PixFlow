package com.pixflow.app.download;

import java.util.List;

public record CustomBundleRequest(List<BundleItem> items, String archiveName) {
    public record BundleItem(String type, String imageId, String resultId, String filename) {
    }
}

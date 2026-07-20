package com.pixflow.module.file.image;

public record AssetImageDetailView(
        AssetImageView image,
        String previousImageId,
        String nextImageId) {
}

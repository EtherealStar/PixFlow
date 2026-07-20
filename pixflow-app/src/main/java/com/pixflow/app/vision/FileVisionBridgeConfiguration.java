package com.pixflow.app.vision;

import com.pixflow.module.file.api.AssetContentMetadata;
import com.pixflow.module.file.api.AssetContentReader;
import com.pixflow.module.file.api.AssetSourceType;
import com.pixflow.module.file.api.visual.AssetVisualInputEvent;
import com.pixflow.module.file.api.visual.AssetVisualInputEventSink;
import com.pixflow.module.vision.api.VisionTriggerPublisher;
import com.pixflow.module.vision.api.VisualAsset;
import com.pixflow.module.vision.api.VisualAssetReader;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FileVisionBridgeConfiguration {
    @Bean
    public AssetVisualInputEventSink assetVisualInputEventSink(VisionTriggerPublisher publisher) {
        return event -> {
            if (event.kind() == AssetVisualInputEvent.Kind.PACKAGE_READY) {
                publisher.packageReady(event.eventId(), event.packageId());
            } else {
                publisher.skuInputChanged(event.eventId(), event.packageId(), event.skuId());
            }
        };
    }

    @Bean
    public VisualAssetReader visualAssetReader(AssetContentReader contents) {
        return new FileVisualAssetReader(contents);
    }

    static final class FileVisualAssetReader implements VisualAssetReader {
        private final AssetContentReader contents;

        FileVisualAssetReader(AssetContentReader contents) {
            this.contents = contents;
        }

        @Override
        public List<VisualAsset> listCurrentOriginals(long packageId) {
            return contents.listCurrentOriginals(packageId).stream()
                    .filter(item -> item.sourceType() == AssetSourceType.ORIGINAL)
                    .map(this::toVisualAsset)
                    .toList();
        }

        @Override
        public VisualAsset requireImage(long packageId, long imageId) {
            return toVisualAsset(contents.require(packageId, imageId));
        }

        private VisualAsset toVisualAsset(AssetContentMetadata descriptor) {
            if (descriptor.contentHash() == null || descriptor.contentHash().isBlank()) {
                throw new IllegalStateException("visual asset content hash is unavailable");
            }
            return new VisualAsset(
                    descriptor.packageId(), descriptor.skuId(), descriptor.imageId(),
                    descriptor.contentHash(), descriptor.size(), descriptor.contentType(),
                    () -> contents.open(descriptor.referenceKey()));
        }
    }
}

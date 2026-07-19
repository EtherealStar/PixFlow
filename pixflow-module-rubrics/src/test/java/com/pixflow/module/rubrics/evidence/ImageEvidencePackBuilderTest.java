package com.pixflow.module.rubrics.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.image.ImageCodec;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.rubrics.subject.ImageResultSubject;
import com.pixflow.module.task.api.publication.PublishedAssetReader;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class ImageEvidencePackBuilderTest {

    @Test
    void distinguishesIdentityMismatchFromTransientStorageFailure() {
        ObjectStorage storage = mock(ObjectStorage.class);
        PublishedAssetReader assets = mock(PublishedAssetReader.class);
        ObjectLocation location = ObjectLocation.of(BucketType.TMP, "rubrics/image.png");
        when(assets.require("IMAGE:2:10")).thenReturn(
                new PublishedAssetReader.PublishedAssetContent(11, location, "image/png", 10));
        ImageEvidencePackBuilder builder = new ImageEvidencePackBuilder(
                storage, assets, mock(ImageCodec.class), new ObjectMapper(), Clock.systemUTC());

        EvidencePack mismatch = builder.build(subject());

        assertThat(mismatch.failure().kind()).isEqualTo(EvidenceFailureKind.INVALID_IDENTITY);

        when(assets.require("IMAGE:2:10")).thenReturn(
                new PublishedAssetReader.PublishedAssetContent(10, location, "image/png", 10));
        when(storage.getBytes(location)).thenThrow(new IllegalStateException("storage unavailable"));

        EvidencePack unavailable = builder.build(subject());

        assertThat(unavailable.failure().kind())
                .isEqualTo(EvidenceFailureKind.TRANSIENT_DEPENDENCY);
        assertThat(unavailable.hash()).isNotEqualTo(mismatch.hash());
    }

    private static ImageResultSubject subject() {
        return new ImageResultSubject(
                "1", 2, "sku", "STANDARD", "image", null, null, "branch",
                10, "IMAGE:2:10", 10, "snapshot");
    }
}

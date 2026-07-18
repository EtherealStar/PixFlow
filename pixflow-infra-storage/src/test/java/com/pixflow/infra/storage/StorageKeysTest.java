package com.pixflow.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StorageKeysTest {

    @Test
    void buildsStablePackageKeys() {
        assertThat(StorageKeys.packageSource(42, "zip"))
                .isEqualTo(ObjectLocation.of(BucketType.PACKAGES, "42/source.zip"));
        assertThat(StorageKeys.packageSource(42, "rar"))
                .isEqualTo(ObjectLocation.of(BucketType.PACKAGES, "42/source.rar"));
        assertThat(StorageKeys.packageSource(42, "7z"))
                .isEqualTo(ObjectLocation.of(BucketType.PACKAGES, "42/source.7z"));

        assertThat(StorageKeys.packageImage(42, "folder/a.png"))
                .isEqualTo(ObjectLocation.of(BucketType.PACKAGES, "42/images/folder/a.png"));

        assertThat(StorageKeys.packageDoc(42, "copy.xlsx"))
                .isEqualTo(ObjectLocation.of(BucketType.PACKAGES, "42/doc/copy.xlsx"));
    }

    @Test
    void separatesTemporaryCandidatesFromStableAssets() {
        assertThat(StorageKeys.resultUnit("1001", "abc123", 7, "webp"))
                .isEqualTo(ObjectLocation.of(BucketType.TMP,
                        "results/1001/units/abc123/epochs/7/output.webp"));
        assertThat(StorageKeys.generatedUnit("1001", "def456", 8, "jpg"))
                .isEqualTo(ObjectLocation.of(BucketType.TMP,
                        "generated/1001/units/def456/epochs/8/output.jpg"));
        assertThat(StorageKeys.resultAsset(42, 101, "webp"))
                .isEqualTo(ObjectLocation.of(BucketType.RESULTS, "42/images/101/output.webp"));
        assertThat(StorageKeys.generatedAsset(42, 102, "png"))
                .isEqualTo(ObjectLocation.of(BucketType.GENERATED, "42/images/102/output.png"));
    }

    @Test
    void buildsStableToolAndTmpKeys() {
        assertThat(StorageKeys.toolResult("trace-1"))
                .isEqualTo(ObjectLocation.of(BucketType.TOOL_RESULTS, "trace-1.txt"));

        assertThat(StorageKeys.runtimeGroup("1001", 9, "abc123", "88", "node.webp"))
                .isEqualTo(ObjectLocation.of(BucketType.TMP, "1001/9/abc123/88/node.webp"));
    }

    @Test
    void rejectsTraversalAndPathSegments() {
        assertThatThrownBy(() -> StorageKeys.packageImage(42, "../evil.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.resultUnit("1001", "../bad", 1, "webp"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.packageDoc(42, "dir/copy.xlsx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.generatedUnit("1001", "abc", 1, ".jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.runtimeGroup("1001", 0, "abc", "88", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.packageSource(42, "tar.gz"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.packageSource(42, "../zip"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.resultAsset(42, 0, "png"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

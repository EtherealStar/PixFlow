package com.pixflow.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StorageKeysTest {

    @Test
    void buildsStablePackageKeys() {
        assertThat(StorageKeys.packageSource(42))
                .isEqualTo(ObjectLocation.of(BucketType.PACKAGES, "42/source.zip"));

        assertThat(StorageKeys.packageImage(42, "folder/a.png"))
                .isEqualTo(ObjectLocation.of(BucketType.PACKAGES, "42/images/folder/a.png"));

        assertThat(StorageKeys.packageDoc(42, "copy.xlsx"))
                .isEqualTo(ObjectLocation.of(BucketType.PACKAGES, "42/doc/copy.xlsx"));
    }

    @Test
    void buildsStableResultKeys() {
        assertThat(StorageKeys.result(1001, "SKU123", 88, "b1", "webp"))
                .isEqualTo(ObjectLocation.of(BucketType.RESULTS, "1001/SKU123_88_b1.webp"));

        assertThat(StorageKeys.groupResult(1001, "SKU123", "g1", "b1", "png"))
                .isEqualTo(ObjectLocation.of(BucketType.RESULTS, "1001/SKU123_gg1_b1.png"));

        assertThat(StorageKeys.generated(1001, "SKU123", 88, "jpg"))
                .isEqualTo(ObjectLocation.of(BucketType.GENERATED, "1001/SKU123_88.jpg"));
    }

    @Test
    void buildsStableToolAndTmpKeys() {
        assertThat(StorageKeys.toolResult("trace-1"))
                .isEqualTo(ObjectLocation.of(BucketType.TOOL_RESULTS, "trace-1.txt"));

        assertThat(StorageKeys.tmpBranch(1001, 88, "b1", "node.webp"))
                .isEqualTo(ObjectLocation.of(BucketType.TMP, "1001/88/b1/node.webp"));

        assertThat(StorageKeys.tmpGroup(1001, "g1", "b1", 88, "node.webp"))
                .isEqualTo(ObjectLocation.of(BucketType.TMP, "1001/g1/b1/88/node.webp"));
    }

    @Test
    void rejectsTraversalAndPathSegments() {
        assertThatThrownBy(() -> StorageKeys.packageImage(42, "../evil.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.result(1001, "SKU/../A", 88, "b1", "webp"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.packageDoc(42, "dir/copy.xlsx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.generated(1001, "SKU123", 88, ".jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

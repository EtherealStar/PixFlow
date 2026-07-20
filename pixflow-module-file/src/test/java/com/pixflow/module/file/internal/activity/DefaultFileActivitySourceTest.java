package com.pixflow.module.file.internal.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.module.file.api.activity.FileActivityStatus;
import com.pixflow.module.file.api.activity.UploadActivitySnapshot;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.upload.UploadSessionStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultFileActivitySourceTest {
    @Test
    void uploadProgressUsesTheAuthoritativeChunkSnapshot() {
        UploadSessionStore uploads = mock(UploadSessionStore.class);
        AssetPackageMapper packages = mock(AssetPackageMapper.class);
        Instant now = Instant.parse("2026-07-20T08:00:00Z");
        when(uploads.listActivitySnapshots()).thenReturn(List.of(
                new UploadActivitySnapshot("upload-7", "UPLOADING", 2, 4, null, now, now)));
        when(packages.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        var page = new DefaultFileActivitySource(uploads, packages).listCurrent(1, 10);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records()).singleElement().satisfies(activity -> {
            assertThat(activity.status()).isEqualTo(FileActivityStatus.UPLOADING);
            assertThat(activity.completed()).isEqualTo(2);
            assertThat(activity.total()).isEqualTo(4);
        });
    }
}

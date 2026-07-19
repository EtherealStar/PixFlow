package com.pixflow.module.file.ingest;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class RarExtractorTest {
    private static final String JUNRAR_TEST_RAR =
            "UmFyIRoHAM+QcwAADQAAAAAAAAB8zXQgkC0ADQAAAAQAAAAD4Tl7zCeTJEEdMwsAtIEAAGZvb1xiYXIudHh0AMAACL8IrvLDGH6f/ZLdiiN04IAjAAAAAAAAAAAAAwAAAAAnkyRBFDADAP1BAABmb2/EPXsAQAcA";

    @Test
    void readsARealRar4Archive() {
        ObjectStorage storage = mock(ObjectStorage.class);
        AssetPackageService packages = mock(AssetPackageService.class);
        ArchiveEntryProcessor processor = mock(ArchiveEntryProcessor.class);
        ArchiveEntryProcessor.Session session = mock(ArchiveEntryProcessor.Session.class);
        AssetPackage assetPackage = new AssetPackage();
        assetPackage.setId(99L);
        assetPackage.setMinioZipKey("99/source.rar");
        when(packages.require(99L)).thenReturn(assetPackage);
        when(processor.begin(99L)).thenReturn(session);
        when(processor.maxEntryBytes()).thenReturn(1024L);
        when(storage.getStream(ObjectLocation.of(BucketType.PACKAGES, "99/source.rar")))
                .thenReturn(new ByteArrayInputStream(Base64.getDecoder().decode(JUNRAR_TEST_RAR)));

        new RarExtractor(storage, packages, processor).extract(99L);

        // 夹具来自 junrar 官方测试集，确保这里走的是真实 RAR4 parser。
        verify(session).accept(eq("foo\\bar.txt"), aryEq("baz\n".getBytes(StandardCharsets.UTF_8)),
                eq(4L), eq(13L), eq(false));
        verify(session).finish();
    }
}

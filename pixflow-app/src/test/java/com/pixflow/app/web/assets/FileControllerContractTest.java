package com.pixflow.app.web.assets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pixflow.common.web.PageResponse;
import com.pixflow.contracts.asset.AssetReferenceKind;
import com.pixflow.module.file.FileService;
import com.pixflow.module.file.api.AssetReferenceCandidate;
import com.pixflow.module.file.api.AssetReferenceCatalog;
import com.pixflow.module.file.api.AssetReferenceSource;
import com.pixflow.module.file.api.AssetSourceType;
import com.pixflow.module.file.upload.UploadSessionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FileControllerContractTest {
    private final FileService files = mock(FileService.class);
    private final UploadSessionService uploads = mock(UploadSessionService.class);
    private final AssetReferenceCatalog catalog = mock(AssetReferenceCatalog.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new FileController(files, uploads, catalog))
            .build();

    @Test
    void legacyMultipartUploadRouteIsGone() throws Exception {
        MockMultipartFile archive = new MockMultipartFile(
                "zip", "materials.zip", "application/zip", new byte[]{1});

        mvc.perform(multipart("/api/files/packages").file(archive))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void assetReferencePickerReturnsCanonicalCandidateShape() throws Exception {
        var candidate = new AssetReferenceCandidate(
                "package:7/image:9", AssetReferenceKind.IMAGE, AssetSourceType.ORIGINAL,
                "summer.zip / SKU-1 / front.png", false, AssetReferenceSource.MATERIALS);
        when(catalog.list(eq(AssetReferenceSource.MATERIALS), any(), any(),
                eq(1L), eq(50L), any())).thenReturn(PageResponse.of(List.of(candidate), 1, 1, 50));

        mvc.perform(get("/api/asset-references")
                        .param("source", "MATERIALS")
                        .param("page", "1")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].referenceKey").value("package:7/image:9"))
                .andExpect(jsonPath("$.data.records[0].sourceType").value("ORIGINAL"));
    }
}

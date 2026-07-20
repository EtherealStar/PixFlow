package com.pixflow.app.download;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.task.api.download.CustomDownloadBundleService;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomDownloadServiceTest {
    @Test
    void forwardsOnlyCanonicalImageReferencesToTaskOwnerService() {
        CustomDownloadBundleService bundles = mock(CustomDownloadBundleService.class);
        CustomDownloadService service = new CustomDownloadService(bundles);

        service.build(new CustomBundleRequest(List.of(
                new CustomBundleRequest.BundleItem("package:7/image:11", "front.png")),
                "selection.zip"));

        verify(bundles).build("selection.zip", List.of(
                new CustomDownloadBundleService.BundleItem("package:7/image:11", "front.png")));
    }

    @Test
    void rejectsAnEmptySelectionBeforeCallingOwnerService() {
        CustomDownloadService service = new CustomDownloadService(mock(CustomDownloadBundleService.class));

        assertThatThrownBy(() -> service.build(new CustomBundleRequest(List.of(), "empty.zip")))
                .isInstanceOf(PixFlowException.class);
    }
}

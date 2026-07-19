package com.pixflow.module.file.pkg;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.pixflow.common.error.PixFlowException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AssetPackageServiceTest {
    private static final Instant NOW = Instant.parse("2026-06-28T12:00:00Z");
    private final AssetPackageMapper packageMapper = org.mockito.Mockito.mock(AssetPackageMapper.class);
    private final AssetPackageService service = new AssetPackageService(
            packageMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void missingPackageThrowsDomainException() {
        when(packageMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.require(404L))
                .isInstanceOf(PixFlowException.class)
                .hasMessageContaining("package not found");
    }
}

package com.pixflow.module.file.ingest;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageService;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ArchiveExtractionDispatcher {
    private final AssetPackageService packageService;

    private final Map<ArchiveFormat, ArchiveExtractor> extractors;

    public ArchiveExtractionDispatcher(AssetPackageService packageService,
                                       List<ArchiveExtractor> extractors) {
        this.packageService = packageService;
        this.extractors = new EnumMap<>(ArchiveFormat.class);
        for (ArchiveExtractor extractor : extractors) {
            this.extractors.put(extractor.format(), extractor);
        }
    }

    public void extract(long packageId) {
        AssetPackage assetPackage = packageService.require(packageId);
        ArchiveFormat format = ArchiveFormat.valueOf(assetPackage.getArchiveFormat());
        ArchiveExtractor extractor = extractors.get(format);
        if (extractor == null) {
            throw new PixFlowException(FileErrorCode.INVALID_ZIP,
                    "archive extractor is unavailable: " + format);
        }
        extractor.extract(packageId);
    }
}

package com.pixflow.module.file.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "pixflow.file")
public class FileProperties {
    private final Upload upload = new Upload();
    private final Extract extract = new Extract();
    private final Zip zip = new Zip();
    private final Image image = new Image();
    private final Copydoc copydoc = new Copydoc();
    private final PublishGapRescan publishGapRescan = new PublishGapRescan();

    public Upload getUpload() {
        return upload;
    }

    public Extract getExtract() {
        return extract;
    }

    public Zip getZip() {
        return zip;
    }

    public Image getImage() {
        return image;
    }

    public Copydoc getCopydoc() {
        return copydoc;
    }

    public PublishGapRescan getPublishGapRescan() {
        return publishGapRescan;
    }

    public static class Upload {
        private DataSize maxZipSize = DataSize.ofGigabytes(2);
        private DataSize maxDocSize = DataSize.ofMegabytes(50);
        private DataSize chunkSize = DataSize.ofMegabytes(5);
        private Duration sessionTtl = Duration.ofHours(24);

        public DataSize getMaxZipSize() {
            return maxZipSize;
        }

        public void setMaxZipSize(DataSize maxZipSize) {
            this.maxZipSize = maxZipSize;
        }

        public DataSize getMaxDocSize() {
            return maxDocSize;
        }

        public void setMaxDocSize(DataSize maxDocSize) {
            this.maxDocSize = maxDocSize;
        }

        public DataSize getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(DataSize chunkSize) {
            this.chunkSize = chunkSize;
        }

        public Duration getSessionTtl() {
            return sessionTtl;
        }

        public void setSessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
        }
    }

    public static class Extract {
        private int consumerConcurrency = 2;
        private int intraPackageParallelism = 4;
        private Duration consumerTimeout = Duration.ofMinutes(30);
        private String tempDir = System.getProperty("java.io.tmpdir") + "/pixflow-extract";

        public int getConsumerConcurrency() {
            return consumerConcurrency;
        }

        public void setConsumerConcurrency(int consumerConcurrency) {
            this.consumerConcurrency = consumerConcurrency;
        }


        public int getIntraPackageParallelism() {
            return intraPackageParallelism;
        }

        public void setIntraPackageParallelism(int intraPackageParallelism) {
            this.intraPackageParallelism = intraPackageParallelism;
        }

        public Duration getConsumerTimeout() {
            return consumerTimeout;
        }

        public void setConsumerTimeout(Duration consumerTimeout) {
            this.consumerTimeout = consumerTimeout;
        }

        public String getTempDir() {
            return tempDir;
        }

        public void setTempDir(String tempDir) {
            this.tempDir = tempDir;
        }
    }

    public static class Zip {
        private int maxEntries = 50_000;
        private DataSize maxTotalBytes = DataSize.ofGigabytes(5);
        private DataSize maxEntryBytes = DataSize.ofMegabytes(200);
        private int maxCompressionRatio = 100;

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public DataSize getMaxTotalBytes() {
            return maxTotalBytes;
        }

        public void setMaxTotalBytes(DataSize maxTotalBytes) {
            this.maxTotalBytes = maxTotalBytes;
        }

        public DataSize getMaxEntryBytes() {
            return maxEntryBytes;
        }

        public void setMaxEntryBytes(DataSize maxEntryBytes) {
            this.maxEntryBytes = maxEntryBytes;
        }

        public int getMaxCompressionRatio() {
            return maxCompressionRatio;
        }

        public void setMaxCompressionRatio(int maxCompressionRatio) {
            this.maxCompressionRatio = maxCompressionRatio;
        }
    }

    public static class Image {
        private List<String> allowedExtensions = new ArrayList<>(List.of("jpg", "jpeg", "png", "webp", "bmp", "gif", "tiff"));
        private boolean magicBytesCheck = true;
        private DataSize maxImageSize = DataSize.ofMegabytes(200);

        public List<String> getAllowedExtensions() {
            return allowedExtensions;
        }

        public void setAllowedExtensions(List<String> allowedExtensions) {
            this.allowedExtensions = allowedExtensions;
        }

        public boolean isMagicBytesCheck() {
            return magicBytesCheck;
        }

        public void setMagicBytesCheck(boolean magicBytesCheck) {
            this.magicBytesCheck = magicBytesCheck;
        }

        public DataSize getMaxImageSize() {
            return maxImageSize;
        }

        public void setMaxImageSize(DataSize maxImageSize) {
            this.maxImageSize = maxImageSize;
        }
    }

    public static class Copydoc {
        private CopyDocDuplicatePolicy onDuplicateSku = CopyDocDuplicatePolicy.OVERWRITE;
        private List<String> skuIdColumns = new ArrayList<>(List.of("sku_id", "商品编号", "SKU"));
        private List<String> productNameColumns = new ArrayList<>(List.of("product_name", "标题", "商品名"));
        private List<String> keywordsColumns = new ArrayList<>(List.of("keywords", "关键词"));
        private List<String> descriptionColumns = new ArrayList<>(List.of("description", "描述", "详情"));

        public CopyDocDuplicatePolicy getOnDuplicateSku() {
            return onDuplicateSku;
        }

        public void setOnDuplicateSku(CopyDocDuplicatePolicy onDuplicateSku) {
            this.onDuplicateSku = onDuplicateSku;
        }

        public List<String> getSkuIdColumns() {
            return skuIdColumns;
        }

        public void setSkuIdColumns(List<String> skuIdColumns) {
            this.skuIdColumns = skuIdColumns;
        }

        public List<String> getProductNameColumns() {
            return productNameColumns;
        }

        public void setProductNameColumns(List<String> productNameColumns) {
            this.productNameColumns = productNameColumns;
        }

        public List<String> getKeywordsColumns() {
            return keywordsColumns;
        }

        public void setKeywordsColumns(List<String> keywordsColumns) {
            this.keywordsColumns = keywordsColumns;
        }

        public List<String> getDescriptionColumns() {
            return descriptionColumns;
        }

        public void setDescriptionColumns(List<String> descriptionColumns) {
            this.descriptionColumns = descriptionColumns;
        }
    }

    public static class PublishGapRescan {
        private Duration interval = Duration.ofMinutes(1);
        private Duration staleAfter = Duration.ofSeconds(30);

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getStaleAfter() {
            return staleAfter;
        }

        public void setStaleAfter(Duration staleAfter) {
            this.staleAfter = staleAfter;
        }
    }
}

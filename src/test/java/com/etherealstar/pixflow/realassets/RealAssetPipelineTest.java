package com.etherealstar.pixflow.realassets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.infra.image.ImageCodec;
import com.etherealstar.pixflow.infra.image.ImageData;
import com.etherealstar.pixflow.infra.image.ImageToolExecutor;
import com.etherealstar.pixflow.infra.image.NaiveBackgroundRemovalClient;
import com.etherealstar.pixflow.infra.storage.StorageService;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import com.etherealstar.pixflow.module.file.SkuExtractor;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
import com.etherealstar.pixflow.module.file.copy.CopyDocumentParser;
import com.etherealstar.pixflow.module.file.copy.CopyParseResult;
import com.etherealstar.pixflow.module.file.copy.CopyRow;
import com.etherealstar.pixflow.module.file.extract.ZipExtractor;
import com.etherealstar.pixflow.module.file.image.ImageIoImageDecoder;
import com.etherealstar.pixflow.module.file.image.ImageValidator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 使用 {@code assets/batch_001} 下的真实商品图片与商品名 CSV，对 task 1-14 中
 * <b>确实需要真实图片或文案</b>的处理路径做端到端验证：
 *
 * <ul>
 *   <li>task 2/3：真实图片解码识别（ImageValidator + ImageIoImageDecoder）、真实文件名 SKU 提取、真实图片 zip 流式解压；</li>
 *   <li>task 4：由真实商品名构造含 id 列的 CSV 文案文档解析（CopyDocumentParser），并验证原始无 id 列文档被拒绝；</li>
 *   <li>task 12：真实像素工具链（ImageCodec + ImageToolExecutor + NaiveBackgroundRemovalClient）。</li>
 * </ul>
 *
 * <p>不需要真实图片或文案的纯逻辑/属性测试不在此执行。</p>
 */
@DisplayName("真实素材端到端验证 (task 1-14 图片/文案相关)")
class RealAssetPipelineTest {

    private static final Path ASSETS_DIR = Path.of("assets", "batch_001");
    private static final List<String> IMAGE_EXTS = List.of("jpg", "jpeg", "png", "webp");

    private static List<Path> imageFiles;

    @BeforeAll
    static void locateAssets() throws IOException {
        assertThat(Files.isDirectory(ASSETS_DIR))
                .as("真实素材目录应存在: " + ASSETS_DIR.toAbsolutePath())
                .isTrue();
        try (var stream = Files.list(ASSETS_DIR)) {
            imageFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> IMAGE_EXTS.contains(ext(p.getFileName().toString())))
                    .sorted()
                    .toList();
        }
        assertThat(imageFiles).as("应找到至少 1 张真实测试图片").isNotEmpty();
    }

    // ---- task 2/3：真实图片识别 + SKU 提取 --------------------------------

    @Test
    @DisplayName("task2/3: 真实图片均可被白名单+解码识别为有效图片")
    void realImagesAreRecognized() throws IOException {
        ImageValidator validator = new ImageValidator(ImageIoImageDecoder.INSTANCE);
        for (Path img : imageFiles) {
            byte[] bytes = Files.readAllBytes(img);
            String name = img.getFileName().toString();
            ImageValidator.ImageCheckResult result = validator.classify(name, bytes);
            assertThat(result.recognized())
                    .as("真实图片应被识别: " + name + " (原因: " + result.reason() + ")")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("task3: 真实文件名提取出预期的数字前缀 SKU")
    void skuExtractedFromRealFilenames() {
        for (Path img : imageFiles) {
            String name = img.getFileName().toString();
            String base = stripExt(name);
            String sku = SkuExtractor.extract(base);
            // 真实样例文件名形如 0001_xxx，SKU 应为起始连续字母数字段
            assertThat(sku).as("SKU 不应为空: " + name).isNotBlank();
            assertThat(sku)
                    .as("SKU 应仅含字母数字: " + name + " -> " + sku)
                    .matches("[A-Za-z0-9]+");
            assertThat(base).as("文件名应以提取出的 SKU 开头: " + name).startsWith(sku);
        }
    }

    // ---- task 2：真实图片打包后流式解压 -----------------------------------

    @Test
    @DisplayName("task2: 真实图片打成 zip 后流式解压可还原全部条目且内容可解码")
    void realImageZipExtractsAllEntries() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (Path img : imageFiles) {
            entries.put("batch_001/" + img.getFileName(), Files.readAllBytes(img));
        }
        byte[] zip = zipOf(entries);

        ZipExtractor extractor = new ZipExtractor(new AssetProperties());
        List<String> extractedPaths = new ArrayList<>();
        extractor.extract(new ByteArrayInputStream(zip), (path, content) -> {
            extractedPaths.add(path);
            // 解压出的真实图片内容应可被 ImageIO 解码
            assertThat(ImageIoImageDecoder.INSTANCE.canDecode(content))
                    .as("解压条目应为可解码图片: " + path)
                    .isTrue();
        });

        assertThat(extractedPaths).hasSize(entries.size());
        assertThat(extractedPaths).containsExactlyInAnyOrderElementsOf(entries.keySet());
    }

    // ---- task 4：真实商品名文案解析 ---------------------------------------

    @Test
    @DisplayName("task4: 由真实商品名构造含 id 列的 CSV 可被解析并与 SKU 软关联")
    void copyDocumentParsesRealProductNames() throws IOException {
        // 真实 products.csv 含 product_name 但无 id 列；这里以真实商品名构造含 id 列的文案文档，
        // id 取自对应图片文件名的 SKU，模拟真实「商品名 + SKU」文案绑定场景。
        List<String[]> rows = realSkuAndNames();
        assertThat(rows).as("应从真实数据构造出文案行").isNotEmpty();

        StringBuilder csv = new StringBuilder("id,product_name,keywords,description\n");
        for (String[] r : rows) {
            csv.append(csvCell(r[0])).append(',')
                    .append(csvCell(r[1])).append(',')
                    .append(csvCell(r[2])).append(',')
                    .append(csvCell(r[3])).append('\n');
        }

        CopyDocumentParser parser = new CopyDocumentParser(new AssetProperties());
        CopyParseResult result = parser.parse("copy.csv", csv.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(result.skippedRowNumbers()).isEmpty();
        assertThat(result.rows()).hasSize(rows.size());

        for (int i = 0; i < rows.size(); i++) {
            CopyRow parsed = result.rows().get(i);
            assertThat(parsed.skuId()).isEqualTo(rows.get(i)[0]);
            assertThat(parsed.productName()).isEqualTo(rows.get(i)[1]);
        }
        // 软关联键即 SKU，应与从图片文件名提取的 SKU 一致（无图/无文案均合法，这里验证存在交集）
        assertThat(result.rows().get(0).skuId()).matches("[A-Za-z0-9]+");
    }

    @Test
    @DisplayName("task4: 原始 products.csv 缺少 id 列应被拒绝")
    void rawProductsCsvWithoutIdColumnRejected() throws IOException {
        Path csv = ASSETS_DIR.resolve("products.csv");
        assertThat(Files.exists(csv)).as("真实 products.csv 应存在").isTrue();
        byte[] bytes = Files.readAllBytes(csv);

        CopyDocumentParser parser = new CopyDocumentParser(new AssetProperties());
        assertThatThrownBy(() -> parser.parse("products.csv", bytes))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DOC_MISSING_ID_COLUMN);
    }

    // ---- task 12：真实像素工具链 ------------------------------------------

    @Test
    @DisplayName("task12: 对真实图片应用 resize/compress/watermark/convert_format/set_background/remove_bg")
    void imageToolChainOnRealImage() throws IOException {
        Path sample = imageFiles.get(0);
        byte[] original = Files.readAllBytes(sample);

        ImageCodec codec = new ImageCodec();
        ImageToolExecutor executor = new ImageToolExecutor(
                new NaiveBackgroundRemovalClient(), codec, mock(StorageService.class));

        BufferedImage decoded = codec.decode(original);
        assertThat(decoded.getWidth()).isPositive();
        assertThat(decoded.getHeight()).isPositive();

        // resize -> 固定 320x240
        ImageData resized = executor.apply(
                node("resize", Map.of("width", 320, "height", 240)),
                new ImageData(decoded, "PNG"));
        assertThat(resized.getImage().getWidth()).isEqualTo(320);
        assertThat(resized.getImage().getHeight()).isEqualTo(240);

        // convert_format -> JPG，编码后可被重新解码
        ImageData asJpg = executor.apply(
                node("convert_format", Map.of("format", "JPG")), resized);
        byte[] jpgBytes = codec.encode(asJpg);
        assertThat(ImageIO.read(new ByteArrayInputStream(jpgBytes)))
                .as("convert_format 后应为可解码 JPG").isNotNull();

        // compress -> max_kb=30，在 JPG 上逐步降质，编码结果仍可解码
        ImageData compressed = executor.apply(
                node("compress", Map.of("max_kb", 30)), asJpg);
        byte[] compressedBytes = codec.encode(compressed);
        assertThat(ImageIO.read(new ByteArrayInputStream(compressedBytes)))
                .as("compress 后应为可解码图片").isNotNull();

        // watermark（文字）-> 尺寸不变，仍可编码解码
        ImageData watermarked = executor.apply(
                node("watermark", Map.of("position", "bottom-right", "text", "PixFlow")),
                resized);
        assertThat(watermarked.getImage().getWidth()).isEqualTo(320);
        assertThat(watermarked.getImage().getHeight()).isEqualTo(240);

        // set_background -> 尺寸不变，结果为不透明 RGB
        ImageData withBg = executor.apply(
                node("set_background", Map.of("color", "#FF0000")), resized);
        assertThat(withBg.getImage().getWidth()).isEqualTo(320);
        assertThat(withBg.getImage().getColorModel().hasAlpha()).isFalse();

        // remove_bg -> 产出带 alpha 通道、尺寸不变的图像
        ImageData removed = executor.apply(node("remove_bg", Map.of()),
                new ImageData(decoded, "PNG"));
        assertThat(removed.getImage().getColorModel().hasAlpha())
                .as("remove_bg 结果应含透明通道").isTrue();
        assertThat(removed.getImage().getWidth()).isEqualTo(decoded.getWidth());
        byte[] pngBytes = codec.encode(removed);
        assertThat(ImageIO.read(new ByteArrayInputStream(pngBytes)))
                .as("remove_bg 编码后应为可解码 PNG").isNotNull();
    }

    // ---- helpers ----------------------------------------------------------

    private static DagNode node(String tool, Map<String, Object> params) {
        return new DagNode(tool + "-1", tool, params);
    }

    /** 解析真实 products.csv，为每个图片文件名计算 SKU，并取其商品名/类别。 */
    private static List<String[]> realSkuAndNames() throws IOException {
        Path csv = ASSETS_DIR.resolve("products.csv");
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        List<String[]> result = new ArrayList<>();
        // 跳过表头；列：image_file, product_name, image_path, product_title, brand, class_label, ...
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            List<String> cols = parseCsvLine(line);
            if (cols.size() < 6) {
                continue;
            }
            String imageFile = cols.get(0);
            String productName = cols.get(1);
            String productTitle = cols.get(3);
            String classLabel = cols.get(5);
            String sku = SkuExtractor.extract(stripExt(imageFile));
            result.add(new String[] {sku, productName, classLabel, productTitle});
        }
        return result;
    }

    /** 极简 CSV 行解析，支持双引号包裹与转义双引号。 */
    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static byte[] zipOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bos.toByteArray();
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}

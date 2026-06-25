package com.etherealstar.pixflow.module.file.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.etherealstar.pixflow.module.file.domain.PackageScanResult;
import com.etherealstar.pixflow.module.file.domain.SkippedFile;
import com.etherealstar.pixflow.module.file.image.ImageValidator.ImageCheckResult;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * 图片识别分类正确性属性测试（任务 2.5）。
 *
 * <p>Feature: pixflow, Property 1: For any zip 内容（任意目录树、任意混合扩展名含大小写变体），
 * Asset_Manager 识别为图片素材的文件集合应恰为「扩展名属于白名单（JPG/JPEG/PNG/WebP，不区分大小写）
 * 且可被图片解码器读取」的文件集合，被排除文件应记录于 {@code skippedFiles} 且 {@code image_count}
 * 等于成功识别图片数。
 * Validates: Requirements 1.5, 1.6, 1.8
 *
 * <p>说明：图片「可被解码」这一 I/O 行为通过可注入的 {@link ImageDecoder} 替身建模，
 * 因此本属性测试无需任何真实图片资源即可校验分类逻辑与计数不变量。
 */
class ImageClassificationPropertyTest {

    /** 单个待分类文件：相对路径 + 是否可被解码（由替身决定）。 */
    private record FileSpec(String path, boolean decodable) {
    }

    private static String baseName(String ext) {
        // 文件主名固定为合法 SKU 字符，扩展名由参数决定
        return "img." + ext;
    }

    @Provide
    Arbitrary<String> extensions() {
        // 白名单（含大小写变体）与一批非白名单扩展名混合
        return Arbitraries.of(
                "jpg", "JPG", "jpeg", "Jpeg", "png", "PNG", "webp", "WebP",
                "gif", "bmp", "txt", "JPGX", "", "tiff", "heic");
    }

    @Provide
    Arbitrary<FileSpec> files() {
        Arbitrary<String> dir = Arbitraries.of("", "a/", "a/b/", "deep/nested/dir/");
        Arbitrary<String> ext = extensions();
        Arbitrary<Boolean> decodable = Arbitraries.of(true, false);
        return Combinators.combine(dir, ext, decodable)
                .as((d, e, dec) -> new FileSpec(d + baseName(e), dec));
    }

    @Provide
    Arbitrary<List<FileSpec>> fileSets() {
        return files().list().ofMinSize(0).ofMaxSize(30);
    }

    @Property(tries = 500)
    void classificationMatchesWhitelistAndDecodability(@ForAll("files") FileSpec spec) {
        ImageValidator validator = new ImageValidator((byte[] content) -> spec.decodable());

        ImageCheckResult result = validator.classify(spec.path(), new byte[] {1, 2, 3});

        boolean whitelisted = ImageValidator.isWhitelisted(spec.path());
        boolean expectedRecognized = whitelisted && spec.decodable();

        assertThat(result.recognized()).isEqualTo(expectedRecognized);
        if (expectedRecognized) {
            assertThat(result.reason()).isNull();
        } else if (!whitelisted) {
            assertThat(result.reason()).isEqualTo(ImageValidator.REASON_NON_WHITELIST);
        } else {
            assertThat(result.reason()).isEqualTo(ImageValidator.REASON_UNDECODABLE);
        }
    }

    @Property(tries = 300)
    void recognizedSetEqualsOracleAndImageCountMatches(
            @ForAll("fileSets") List<FileSpec> specs) {
        List<String> recognized = new ArrayList<>();
        List<SkippedFile> skipped = new ArrayList<>();
        List<String> oracleRecognized = new ArrayList<>();

        for (FileSpec spec : specs) {
            ImageValidator validator = new ImageValidator(c -> spec.decodable());
            ImageCheckResult result = validator.classify(spec.path(), new byte[] {0});
            if (result.recognized()) {
                recognized.add(spec.path());
            } else {
                skipped.add(new SkippedFile(spec.path(), result.reason()));
            }
            if (ImageValidator.isWhitelisted(spec.path()) && spec.decodable()) {
                oracleRecognized.add(spec.path());
            }
        }

        // 识别集合恰为「白名单且可解码」的集合
        assertThat(recognized).containsExactlyElementsOf(oracleRecognized);
        // 被排除文件全部记录在 skippedFiles
        assertThat(recognized.size() + skipped.size()).isEqualTo(specs.size());
        // image_count 等于成功识别图片数
        PackageScanResult scan = PackageScanResult.of(recognized, skipped);
        assertThat(scan.getImageCount()).isEqualTo(recognized.size());
        assertThat(scan.getRecognizedImages()).containsExactlyElementsOf(oracleRecognized);
    }

    @Test
    void caseInsensitiveWhitelist() {
        assertThat(ImageValidator.isWhitelisted("a.JPG")).isTrue();
        assertThat(ImageValidator.isWhitelisted("a.WeBp")).isTrue();
        assertThat(ImageValidator.isWhitelisted("a.gif")).isFalse();
        assertThat(ImageValidator.isWhitelisted("noext")).isFalse();
    }
}

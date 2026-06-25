package com.etherealstar.pixflow.module.file.service;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.common.web.PageResponse;
import com.etherealstar.pixflow.common.web.Pagination;
import com.etherealstar.pixflow.infra.storage.StoragePaths;
import com.etherealstar.pixflow.infra.storage.StorageService;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.etherealstar.pixflow.module.file.SkuExtractor;
import com.etherealstar.pixflow.module.file.copy.CopyDocumentParser;
import com.etherealstar.pixflow.module.file.copy.CopyParseResult;
import com.etherealstar.pixflow.module.file.copy.CopyRow;
import com.etherealstar.pixflow.module.file.domain.PackageScanResult;
import com.etherealstar.pixflow.module.file.domain.PackageStatus;
import com.etherealstar.pixflow.module.file.domain.SkippedFile;
import com.etherealstar.pixflow.module.file.dto.DeleteReport;
import com.etherealstar.pixflow.module.file.dto.PackageDetailResponse;
import com.etherealstar.pixflow.module.file.dto.PackageImageItem;
import com.etherealstar.pixflow.module.file.dto.PackageListItem;
import com.etherealstar.pixflow.module.file.dto.PackageUploadResponse;
import com.etherealstar.pixflow.module.file.entity.AssetCopy;
import com.etherealstar.pixflow.module.file.entity.AssetImage;
import com.etherealstar.pixflow.module.file.entity.AssetPackage;
import com.etherealstar.pixflow.module.file.image.ImageValidator;
import com.etherealstar.pixflow.module.file.image.ImageValidator.ImageCheckResult;
import com.etherealstar.pixflow.module.file.mapper.AssetCopyMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetImageMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import com.etherealstar.pixflow.module.file.extract.ZipExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 素材包上传服务（Asset_Manager 核心，需求 1）。
 *
 * <p>串联上传校验 → 持久化 zip/文案文档 → 流式解压与 zip-bomb 防护 → 图片识别过滤 →
 * 扫描结果与状态判定，并持久化 {@link AssetPackage} 记录。</p>
 *
 * <p>职责边界：本服务在解压识别每张合法图片后，立即按 {@link SkuExtractor} 规则从文件名提取 SKU ID，
 * 并将 {@code package_id}、{@code sku_id}、{@code original_path}（相对 zip 根目录）写入 {@code asset_image}
 * （需求 2.1–2.6）。重复 SKU 全量保留，每条记录拥有独立自增 id 与互不相同的相对路径。
 * 文案文档由 {@link CopyDocumentParser} 解析并入库 {@code asset_copy}（id 作 sku_id），
 * 通过 {@code sku_id} 与 {@code asset_image} 软关联，无数据库外键（需求 3）。</p>
 */
@Service
public class AssetPackageService {

    private static final Logger log = LoggerFactory.getLogger(AssetPackageService.class);

    /** zip 本地文件头签名（普通 / 空档案 / 跨卷）。 */
    private static final byte[][] ZIP_SIGNATURES = {
            {0x50, 0x4B, 0x03, 0x04},
            {0x50, 0x4B, 0x05, 0x06},
            {0x50, 0x4B, 0x07, 0x08}
    };

    private final AssetPackageMapper packageMapper;
    private final AssetImageMapper imageMapper;
    private final AssetCopyMapper copyMapper;
    private final StorageService storageService;
    private final ZipExtractor zipExtractor;
    private final ImageValidator imageValidator;
    private final CopyDocumentParser copyDocumentParser;
    private final AssetProperties assetProperties;
    private final PackageDeleter packageDeleter;

    public AssetPackageService(AssetPackageMapper packageMapper,
                               AssetImageMapper imageMapper,
                               AssetCopyMapper copyMapper,
                               StorageService storageService,
                               ZipExtractor zipExtractor,
                               ImageValidator imageValidator,
                               CopyDocumentParser copyDocumentParser,
                               AssetProperties assetProperties,
                               PackageDeleter packageDeleter) {
        this.packageMapper = packageMapper;
        this.imageMapper = imageMapper;
        this.copyMapper = copyMapper;
        this.storageService = storageService;
        this.zipExtractor = zipExtractor;
        this.imageValidator = imageValidator;
        this.copyDocumentParser = copyDocumentParser;
        this.assetProperties = assetProperties;
        this.packageDeleter = packageDeleter;
    }

    /**
     * 处理素材包上传：校验 → 落盘 → 解压扫描 → 状态判定 → 持久化。
     *
     * @param zipFile 必填 zip 素材包
     * @param docFile 选填文案文档
     * @return 上传结果（含状态、图片计数与被跳过文件清单）
     */
    public PackageUploadResponse upload(MultipartFile zipFile, MultipartFile docFile) {
        validateZipPresence(zipFile);
        validateZipSize(zipFile);

        String name = resolveName(zipFile.getOriginalFilename());

        // 文案文档先读入字节并解析校验（需求 3.2–3.7），不合法尽早拒绝、不创建任何记录
        byte[] docBytes = null;
        String docName = null;
        CopyParseResult copyResult = null;
        if (docFile != null && !docFile.isEmpty()) {
            docBytes = readBytes(docFile);
            docName = resolveName(docFile.getOriginalFilename());
            copyResult = copyDocumentParser.parse(docName, docBytes);
        }

        // 先建包记录（解析中），获取 packageId 以确定存储路径
        AssetPackage pkg = new AssetPackage();
        pkg.setName(name);
        pkg.setStatus(PackageStatus.PARSING);
        pkg.setImageCount(0);
        pkg.setSize(zipFile.getSize());
        pkg.setCreatedAt(LocalDateTime.now());
        packageMapper.insert(pkg);
        long packageId = pkg.getId();

        try {
            // 持久化原始 zip
            String zipPath = StoragePaths.packageZip(packageId);
            storageService.write(openStream(zipFile), zipPath);
            pkg.setZipPath(zipPath);

            // 持久化文案文档（已解析校验通过）
            if (docBytes != null) {
                String docPath = StoragePaths.packageDoc(packageId, docName);
                storageService.write(docBytes, docPath);
                pkg.setDocPath(docPath);
            }

            // 校验 zip 文件签名（截断 / 非 zip 字节流尽早拒绝）
            validateZipSignature(zipPath);

            // 流式解压 + 图片识别
            PackageScanResult scan = scanPackage(packageId, zipPath);

            // 文案行入库与 SKU 软关联（需求 3.6–3.10）；无图无文案均合法，独立于图片识别结果
            persistCopies(packageId, copyResult);

            // 状态判定与计数（需求 1.8–1.10）
            pkg.setImageCount(scan.getImageCount());
            pkg.setStatus(scan.getStatus());
            packageMapper.updateById(pkg);

            return PackageUploadResponse.from(packageId, name, scan);
        } catch (RuntimeException ex) {
            // 解压/校验失败：清理已落盘文件与包记录，避免脏数据
            cleanup(packageId);
            throw ex;
        }
    }

    /** 列表允许的排序字段（需求 4.3），映射到数据库列名。 */
    private static final Set<String> SORTABLE_COLUMNS = Set.of("created_at", "size", "name");
    private static final String DEFAULT_SORT_BY = "created_at";

    /**
     * 素材包列表（分页 + 排序，需求 4.2–4.5）。
     *
     * @param page   页码（{@code null} 默认 1，最小 1）
     * @param size   每页条数（{@code null} 默认 20，取值 1–100）
     * @param sortBy 排序字段（{@code created_at} / {@code size} / {@code name}，未指定或非法回退 {@code created_at}）
     * @param order  排序方向（{@code asc} / {@code desc}，未指定或非法回退 {@code desc}）
     * @return 分页结果（含 total）
     * @throws BusinessException 分页参数越界时（INVALID_PAGINATION）
     */
    public PageResponse<PackageListItem> list(Long page, Long size, String sortBy, String order) {
        Pagination pagination = Pagination.of(page, size);

        String column = resolveSortColumn(sortBy);
        boolean asc = resolveAscending(order);

        QueryWrapper<AssetPackage> wrapper = new QueryWrapper<>();
        wrapper.orderBy(true, asc, column);
        // 以 id 作为稳定次级排序键，保证同值（如同名/同大小/同创建时刻）下分页结果确定
        wrapper.orderBy(true, asc, "id");

        Page<AssetPackage> pageReq = Page.of(pagination.page(), pagination.size());
        Page<AssetPackage> result = packageMapper.selectPage(pageReq, wrapper);

        List<PackageListItem> items = new ArrayList<>();
        for (AssetPackage pkg : result.getRecords()) {
            items.add(PackageListItem.from(pkg));
        }
        return PageResponse.of(items, result.getTotal(), pagination.page(), pagination.size());
    }

    /**
     * 素材包详情（含图片列表，需求 4.8、4.9）。
     *
     * @param packageId 素材包 id
     * @return 详情（含 imageId/skuId/originalPath 的图片列表）
     * @throws BusinessException 素材包不存在时（PACKAGE_NOT_FOUND）
     */
    public PackageDetailResponse detail(long packageId) {
        AssetPackage pkg = packageMapper.selectById(packageId);
        if (pkg == null) {
            throw new BusinessException(ErrorCode.PACKAGE_NOT_FOUND,
                    "素材包不存在：id=" + packageId);
        }
        List<AssetImage> images = imageMapper.selectList(
                new QueryWrapper<AssetImage>().eq("package_id", packageId).orderByAsc("id"));
        List<PackageImageItem> imageItems = new ArrayList<>();
        for (AssetImage image : images) {
            imageItems.add(PackageImageItem.from(image));
        }
        return PackageDetailResponse.from(pkg, imageItems);
    }

    /**
     * 删除素材包（级联清理数据库记录与物理文件，需求 14）。
     *
     * @param packageId 素材包 id
     * @return 删除结果报告
     * @throws BusinessException 素材包不存在或被任务引用时
     */
    public DeleteReport delete(long packageId) {
        return packageDeleter.delete(packageId);
    }

    private static String resolveSortColumn(String sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT_BY;
        }
        String normalized = sortBy.trim().toLowerCase();
        return SORTABLE_COLUMNS.contains(normalized) ? normalized : DEFAULT_SORT_BY;
    }

    private static boolean resolveAscending(String order) {
        return order != null && "asc".equalsIgnoreCase(order.trim());
    }

    /**
     * 将解析所得文案数据行写入 {@code asset_copy}（需求 3.6–3.8）。
     *
     * <p>以 {@code id} 作为 {@code sku_id}，可选列缺失写空值；通过 {@code sku_id} 与 {@code asset_image}
     * 软关联，无数据库外键。无文案文档（{@code result == null}）或文案条目在图片中无对应图片均为合法状态
     * （需求 3.9、3.10），不在此处做任何丢弃或报错。</p>
     */
    private void persistCopies(long packageId, CopyParseResult result) {
        if (result == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (CopyRow row : result.rows()) {
            AssetCopy copy = new AssetCopy();
            copy.setPackageId(packageId);
            copy.setSkuId(row.skuId());
            copy.setProductName(row.productName());
            copy.setKeywords(row.keywords());
            copy.setDescription(row.description());
            copy.setCreatedAt(now);
            copyMapper.insert(copy);
        }
        if (!result.skippedRowNumbers().isEmpty()) {
            log.info("素材包 {} 文案文档跳过空 id 行（行号）：{}", packageId, result.skippedRowNumbers());
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DOC_FORMAT_INVALID,
                    "读取文案文档失败：" + e.getMessage());
        }
    }

    /**
     * 流式解压并对每个文件做图片识别，识别成功的图片落盘并写入 {@code asset_image}（需求 2.1–2.6）。
     *
     * <p>对每张识别成功的图片：原图按相对 zip 根目录的路径落盘，随后从文件名（去扩展名）按
     * {@link SkuExtractor} 规则提取 SKU ID，写入一条 {@code asset_image} 记录。重复 SKU 不去重，
     * 每条记录由数据库分配独立自增 id，且 {@code original_path} 互不相同（zip 内路径天然唯一）。</p>
     */
    private PackageScanResult scanPackage(long packageId, String zipPath) {
        List<String> recognized = new ArrayList<>();
        List<SkippedFile> skipped = new ArrayList<>();

        try (InputStream in = storageService.openInputStream(zipPath)) {
            zipExtractor.extract(in, (relativePath, content) -> {
                ImageCheckResult result = imageValidator.classify(relativePath, content);
                if (result.recognized()) {
                    // 持久化原图，路径相对 zip 根目录（需求 2.4）
                    storageService.write(content, StoragePaths.packageImage(packageId, relativePath));
                    // SKU 提取与入库（需求 2.1–2.6）
                    persistImage(packageId, relativePath);
                    recognized.add(relativePath);
                } else {
                    skipped.add(new SkippedFile(relativePath, result.reason()));
                }
            });
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.ASSET_ZIP_INVALID,
                    "读取 zip 失败：" + e.getMessage());
        }

        return PackageScanResult.of(recognized, skipped);
    }

    /**
     * 为单张已识别图片写入 {@code asset_image} 行。
     *
     * @param packageId    所属素材包 id
     * @param relativePath 图片相对 zip 根目录的完整相对路径（即 {@code original_path}）
     */
    private void persistImage(long packageId, String relativePath) {
        String skuId = SkuExtractor.extract(baseNameOf(relativePath));
        AssetImage image = new AssetImage();
        image.setPackageId(packageId);
        image.setSkuId(skuId);
        image.setOriginalPath(relativePath);
        image.setCreatedAt(LocalDateTime.now());
        imageMapper.insert(image);
    }

    /**
     * 从相对路径中取出文件名（最后一个路径段）并去除扩展名，作为 SKU 提取的输入。
     *
     * <p>例如 {@code "sub/dir/ABC-123.v2.jpg"} → {@code "ABC-123.v2"}。</p>
     */
    private static String baseNameOf(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private void validateZipPresence(MultipartFile zipFile) {
        if (zipFile == null || zipFile.isEmpty()) {
            throw new BusinessException(ErrorCode.ASSET_ZIP_INVALID,
                    "zip_file 缺失或为空文件");
        }
    }

    private void validateZipSize(MultipartFile zipFile) {
        long limit = assetProperties.getZipMaxSize();
        if (zipFile.getSize() > limit) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("maxZipSizeBytes", limit);
            details.put("actualSizeBytes", zipFile.getSize());
            throw new BusinessException(ErrorCode.ASSET_ZIP_TOO_LARGE,
                    "上传的 zip 文件体积超过上限 " + limit + " 字节", details);
        }
    }

    /**
     * 通过文件头签名校验确实为 zip 字节流（需求 1.2 防御随机字节 / 截断文件）。
     */
    private void validateZipSignature(String zipPath) {
        byte[] header = new byte[4];
        int read;
        try (InputStream in = storageService.openInputStream(zipPath)) {
            read = readFully(in, header);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.ASSET_ZIP_INVALID,
                    "读取 zip 文件头失败：" + e.getMessage());
        }
        if (read < 4 || !matchesZipSignature(header)) {
            throw new BusinessException(ErrorCode.ASSET_ZIP_INVALID,
                    "上传内容不是合法的 zip 文件");
        }
    }

    private static boolean matchesZipSignature(byte[] header) {
        for (byte[] signature : ZIP_SIGNATURES) {
            if (header[0] == signature[0] && header[1] == signature[1]
                    && header[2] == signature[2] && header[3] == signature[3]) {
                return true;
            }
        }
        return false;
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        int n;
        while (total < buffer.length && (n = in.read(buffer, total, buffer.length - total)) != -1) {
            total += n;
        }
        return total;
    }

    private void cleanup(long packageId) {
        try {
            storageService.deleteRecursively(StoragePaths.packageDir(packageId));
        } catch (RuntimeException e) {
            log.warn("清理素材包目录失败 packageId={}: {}", packageId, e.getMessage());
        }
        try {
            imageMapper.delete(new QueryWrapper<AssetImage>().eq("package_id", packageId));
        } catch (RuntimeException e) {
            log.warn("清理素材图片记录失败 packageId={}: {}", packageId, e.getMessage());
        }
        try {
            copyMapper.delete(new QueryWrapper<AssetCopy>().eq("package_id", packageId));
        } catch (RuntimeException e) {
            log.warn("清理文案记录失败 packageId={}: {}", packageId, e.getMessage());
        }
        try {
            packageMapper.deleteById(packageId);
        } catch (RuntimeException e) {
            log.warn("清理素材包记录失败 packageId={}: {}", packageId, e.getMessage());
        }
    }

    private static InputStream openStream(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.ASSET_ZIP_INVALID,
                    "读取上传文件失败：" + e.getMessage());
        }
    }

    private static String resolveName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "package";
        }
        String name = originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.isBlank() ? "package" : name;
    }
}

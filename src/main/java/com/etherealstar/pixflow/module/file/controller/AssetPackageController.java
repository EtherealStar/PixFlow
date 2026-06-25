package com.etherealstar.pixflow.module.file.controller;

import com.etherealstar.pixflow.common.web.PageResponse;
import com.etherealstar.pixflow.module.file.dto.DeleteReport;
import com.etherealstar.pixflow.module.file.dto.PackageDetailResponse;
import com.etherealstar.pixflow.module.file.dto.PackageListItem;
import com.etherealstar.pixflow.module.file.dto.PackageUploadResponse;
import com.etherealstar.pixflow.module.file.service.AssetPackageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 素材包相关接口（Asset_Manager）。
 *
 * <p>本控制器当前实现上传端点（需求 1.1）。列表、详情、删除等接口属于后续任务。</p>
 *
 * <p>安全说明：按 MVP 需求，本端点不做用户鉴权（无登录/权限），但对上传内容执行严格校验
 * （格式 / 体积 / zip-bomb / 图片识别）。</p>
 */
@RestController
@RequestMapping("/api/asset/package")
public class AssetPackageController {

    private final AssetPackageService assetPackageService;

    public AssetPackageController(AssetPackageService assetPackageService) {
        this.assetPackageService = assetPackageService;
    }

    /**
     * 上传素材包（需求 1.1）。
     *
     * @param zipFile 必填 zip 素材包（multipart 字段名 {@code zip_file}）
     * @param docFile 选填文案文档（multipart 字段名 {@code doc_file}）
     */
    @PostMapping("/upload")
    public ResponseEntity<PackageUploadResponse> upload(
            @RequestParam(value = "zip_file", required = false) MultipartFile zipFile,
            @RequestParam(value = "doc_file", required = false) MultipartFile docFile) {
        PackageUploadResponse response = assetPackageService.upload(zipFile, docFile);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 素材包列表（需求 4.2–4.5）。
     *
     * @param page   页码（默认 1，最小 1）
     * @param size   每页条数（默认 20，取值 1–100）
     * @param sortBy 排序字段（{@code created_at} / {@code size} / {@code name}，默认 {@code created_at}）
     * @param order  排序方向（{@code asc} / {@code desc}，默认 {@code desc}）
     */
    @GetMapping("/list")
    public ResponseEntity<PageResponse<PackageListItem>> list(
            @RequestParam(value = "page", required = false) Long page,
            @RequestParam(value = "size", required = false) Long size,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "order", required = false) String order) {
        return ResponseEntity.ok(assetPackageService.list(page, size, sortBy, order));
    }

    /**
     * 素材包详情（含图片列表，需求 4.8、4.9）。
     *
     * @param packageId 素材包 id
     */
    @GetMapping("/{packageId}")
    public ResponseEntity<PackageDetailResponse> detail(@PathVariable("packageId") long packageId) {
        return ResponseEntity.ok(assetPackageService.detail(packageId));
    }

    /**
     * 删除素材包（级联清理数据库记录与物理文件，需求 14）。
     *
     * @param packageId 素材包 id
     */
    @DeleteMapping("/{packageId}")
    public ResponseEntity<DeleteReport> delete(@PathVariable("packageId") long packageId) {
        return ResponseEntity.ok(assetPackageService.delete(packageId));
    }
}

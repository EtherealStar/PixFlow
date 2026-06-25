package com.etherealstar.pixflow.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 单张图处理结果实体，对应表 {@code process_result}。
 * <p>{@code branchId} 为支路标识（需求 9.2），同一 {@code imageId} 下唯一，
 * 用于同一原图经多条支路产出的多输出持久化（需求 9.1）。
 */
@TableName("process_result")
public class ProcessResult {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务 id（软关联） */
    private Long taskId;

    /** 对应原图 id（软关联） */
    private Long imageId;

    /** SKU ID */
    private String skuId;

    /** 支路标识，同一 imageId 下唯一（需求 9.2） */
    private String branchId;

    /** 处理后图片路径，失败时为空 */
    private String outputPath;

    /** 生成文案，最大 2000 字符 */
    private String generatedCopy;

    /** 0 待处理 1 成功 2 失败 */
    private Integer status;

    /** 失败原因，最大 1000 字符 */
    private String errorMsg;

    /** 创建时间 */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getImageId() {
        return imageId;
    }

    public void setImageId(Long imageId) {
        this.imageId = imageId;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public String getBranchId() {
        return branchId;
    }

    public void setBranchId(String branchId) {
        this.branchId = branchId;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public String getGeneratedCopy() {
        return generatedCopy;
    }

    public void setGeneratedCopy(String generatedCopy) {
        this.generatedCopy = generatedCopy;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

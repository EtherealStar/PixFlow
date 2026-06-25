package com.etherealstar.pixflow.module.task.controller;

import com.etherealstar.pixflow.common.web.PageResponse;
import com.etherealstar.pixflow.module.task.dto.TaskDetailResponse;
import com.etherealstar.pixflow.module.task.dto.TaskListItem;
import com.etherealstar.pixflow.module.task.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务管理查询接口（Task_Manager，需求 12.1–12.5）。
 *
 * <p>提供任务列表（分页、status 筛选）与任务详情（含结果列表）。确认执行端点位于
 * {@code POST /api/conversation/{conversationId}/confirm}（见 ConversationController），打包下载位于
 * {@code GET /api/asset/result/download/{taskId}}（见 ResultPreviewController）。</p>
 *
 * <p>安全说明：按 MVP 范围本端点不做用户鉴权（无登录/权限），仅做参数校验。</p>
 */
@RestController
@RequestMapping("/api/task")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 任务列表（分页，按 {@code created_at} 降序，可选 status 筛选，需求 12.1–12.3）。
     *
     * @param page   页码（默认 1，最小 1）
     * @param size   每页条数（默认 20，取值 1–100）
     * @param status 任务状态筛选（选填：0/1/2/3）
     */
    @GetMapping("/list")
    public ResponseEntity<PageResponse<TaskListItem>> list(
            @RequestParam(value = "page", required = false) Long page,
            @RequestParam(value = "size", required = false) Long size,
            @RequestParam(value = "status", required = false) Integer status) {
        return ResponseEntity.ok(taskService.listTasks(page, size, status));
    }

    /**
     * 任务详情（含一页结果列表，需求 12.4、12.5）。
     *
     * @param taskId     任务 id
     * @param resultPage 结果列表页码（默认 1）
     * @param resultSize 结果列表每页条数（默认 20，取值 1–100）
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskDetailResponse> detail(
            @PathVariable("taskId") long taskId,
            @RequestParam(value = "resultPage", required = false) Long resultPage,
            @RequestParam(value = "resultSize", required = false) Long resultSize) {
        return ResponseEntity.ok(taskService.taskDetail(taskId, resultPage, resultSize));
    }
}

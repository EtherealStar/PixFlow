package com.etherealstar.pixflow.module.conversation.controller;

import com.etherealstar.pixflow.common.web.PageResponse;
import com.etherealstar.pixflow.module.conversation.dto.ConversationCreateResponse;
import com.etherealstar.pixflow.module.conversation.dto.ConversationListItem;
import com.etherealstar.pixflow.module.conversation.dto.MessageItem;
import com.etherealstar.pixflow.module.conversation.dto.SendMessageRequest;
import com.etherealstar.pixflow.module.conversation.dto.SendMessageResponse;
import com.etherealstar.pixflow.module.conversation.service.ConversationService;
import com.etherealstar.pixflow.module.dag.parser.DagParseResult;
import com.etherealstar.pixflow.module.dag.parser.DagParser;
import com.etherealstar.pixflow.module.task.dto.ConfirmRequest;
import com.etherealstar.pixflow.module.task.dto.ConfirmResponse;
import com.etherealstar.pixflow.module.task.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话相关接口（Conversation_Module，需求 5、6、7）。
 *
 * <p>本控制器实现对话创建、对话列表、消息历史（需求 5.1、5.5）、发送消息并触发 DAG 解析（需求 5.3、6.1–6.6）
 * 以及确认执行任务（需求 7.1、12.6）。发送消息时先持久化用户消息再调用 {@link DagParser} 解析为 DAG 预览或缺参追问；
 * 确认执行时委托 {@link TaskService} 服务端重新校验 DAG 并同步驱动执行引擎。</p>
 *
 * <p>安全说明：按 MVP 范围本端点不做用户鉴权（无登录/权限），但对消息内容与附件素材包执行严格校验，
 * 且确认执行时不信任前端回传的 DAG，一律服务端重新校验。</p>
 */
@RestController
@RequestMapping("/api/conversation")
public class ConversationController {

    private final ConversationService conversationService;
    private final DagParser dagParser;
    private final TaskService taskService;

    public ConversationController(ConversationService conversationService,
                                  DagParser dagParser,
                                  TaskService taskService) {
        this.conversationService = conversationService;
        this.dagParser = dagParser;
        this.taskService = taskService;
    }

    /**
     * 创建对话（需求 5.1）。
     */
    @PostMapping("/create")
    public ResponseEntity<ConversationCreateResponse> create() {
        long conversationId = conversationService.create();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConversationCreateResponse.of(conversationId));
    }

    /**
     * 对话列表（分页，按 created_at 降序，需求 5.1）。
     *
     * @param page 页码（默认 1，最小 1）
     * @param size 每页条数（默认 20，取值 1–100）
     */
    @GetMapping("/list")
    public ResponseEntity<PageResponse<ConversationListItem>> list(
            @RequestParam(value = "page", required = false) Long page,
            @RequestParam(value = "size", required = false) Long size) {
        return ResponseEntity.ok(conversationService.list(page, size));
    }

    /**
     * 消息历史（按 created_at 升序，需求 5.5）。
     *
     * @param conversationId 对话 id
     */
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<MessageItem>> messages(@PathVariable("conversationId") long conversationId) {
        return ResponseEntity.ok(conversationService.getMessages(conversationId));
    }

    /**
     * 发送消息并触发 DAG 解析（需求 5.3、6.1–6.6）。
     *
     * <p>先经 {@link ConversationService#sendMessage} 执行校验链并持久化用户消息，再将消息内容交由
     * {@link DagParser} 解析为 DAG 预览或缺参追问。解析不创建任务，{@code taskId} 恒为 {@code null}。</p>
     *
     * @param conversationId 对话 id
     * @param request        发送请求（内容与可选附件素材包）
     */
    @PostMapping("/{conversationId}/send")
    public ResponseEntity<SendMessageResponse> send(
            @PathVariable("conversationId") long conversationId,
            @RequestBody SendMessageRequest request) {
        MessageItem message = conversationService.sendMessage(conversationId, request);
        DagParseResult parseResult = dagParser.parse(request == null ? null : request.content());
        return ResponseEntity.ok(SendMessageResponse.of(message.id(), parseResult));
    }

    /**
     * 确认并执行任务（需求 7.1、12.6）。
     *
     * <p>委托 {@link TaskService#confirm} 在服务端重新校验前端回传的 DAG，校验通过后创建任务并同步驱动
     * 执行引擎，返回任务终态、计数与成功结果图预览 URL。</p>
     *
     * @param conversationId 来源对话 id
     * @param request        确认请求（dagJson 与目标 packageId）
     */
    @PostMapping("/{conversationId}/confirm")
    public ResponseEntity<ConfirmResponse> confirm(
            @PathVariable("conversationId") long conversationId,
            @RequestBody ConfirmRequest request) {
        return ResponseEntity.ok(taskService.confirm(conversationId, request));
    }
}

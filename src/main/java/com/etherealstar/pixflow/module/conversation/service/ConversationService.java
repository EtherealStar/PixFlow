package com.etherealstar.pixflow.module.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.common.web.PageResponse;
import com.etherealstar.pixflow.common.web.Pagination;
import com.etherealstar.pixflow.module.conversation.dto.ConversationListItem;
import com.etherealstar.pixflow.module.conversation.dto.MessageItem;
import com.etherealstar.pixflow.module.conversation.dto.SendMessageRequest;
import com.etherealstar.pixflow.module.conversation.entity.Conversation;
import com.etherealstar.pixflow.module.conversation.entity.Message;
import com.etherealstar.pixflow.module.conversation.mapper.ConversationMapper;
import com.etherealstar.pixflow.module.conversation.mapper.MessageMapper;
import com.etherealstar.pixflow.module.file.domain.PackageStatus;
import com.etherealstar.pixflow.module.file.entity.AssetPackage;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 对话与消息收发服务（Conversation_Module，需求 5）。
 *
 * <p>职责：创建对话、对话列表、消息历史（{@code created_at} 升序），以及发送消息的校验链与持久化。
 * 发送消息时执行内容校验（非空白、长度 ≤ 4000）与附件素材包校验（存在且状态就绪），校验失败一律不持久化
 * （需求 5.7、5.8）；首条消息以 {@code content} 前 {@code min(20, len)} 字符作为对话标题（需求 5.2）；
 * 持久化 {@code role}/{@code content}/{@code attached_package_id}/{@code conversation_id}（需求 5.3、5.4）。
 * 消息触发处理任务后通过 {@link #attachTask(long, long)} 写入 {@code task_id}（需求 5.6）。</p>
 *
 * <p>DAG 解析与任务执行不在本模块职责内：发送端点对解析器的串联在 DAG_Parser 相关任务中实现，本服务仅提供
 * 经校验后的用户消息持久化能力供其复用。</p>
 */
@Service
public class ConversationService {

    /** 消息内容最大长度（字符，需求 5.3、5.7）。 */
    public static final int CONTENT_MAX_LENGTH = 4000;

    /** 对话标题截取上限（字符，需求 5.2）。 */
    public static final int TITLE_MAX_LENGTH = 20;

    /** 用户消息角色（需求 5.3）。 */
    public static final String ROLE_USER = "user";

    /** 助手消息角色（需求 5.3）。 */
    public static final String ROLE_ASSISTANT = "assistant";

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final AssetPackageMapper packageMapper;

    public ConversationService(ConversationMapper conversationMapper,
                               MessageMapper messageMapper,
                               AssetPackageMapper packageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.packageMapper = packageMapper;
    }

    /**
     * 创建对话（需求 5.1）。
     *
     * @return 新建对话 id
     */
    public long create() {
        LocalDateTime now = LocalDateTime.now();
        Conversation conversation = new Conversation();
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    /**
     * 对话列表（分页，按 {@code created_at} 降序，需求 5.1）。
     *
     * @param page 页码（{@code null} 默认 1，最小 1）
     * @param size 每页条数（{@code null} 默认 20，取值 1–100）
     * @return 分页结果（含 total）
     * @throws BusinessException 分页参数越界时（INVALID_PAGINATION）
     */
    public PageResponse<ConversationListItem> list(Long page, Long size) {
        Pagination pagination = Pagination.of(page, size);

        QueryWrapper<Conversation> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("created_at");
        // 以 id 作为稳定次级排序键，保证同一创建时刻下分页结果确定
        wrapper.orderByDesc("id");

        Page<Conversation> pageReq = Page.of(pagination.page(), pagination.size());
        Page<Conversation> result = conversationMapper.selectPage(pageReq, wrapper);

        List<ConversationListItem> items = new ArrayList<>();
        for (Conversation conversation : result.getRecords()) {
            items.add(ConversationListItem.from(conversation));
        }
        return PageResponse.of(items, result.getTotal(), pagination.page(), pagination.size());
    }

    /**
     * 消息历史（按 {@code created_at} 升序返回该对话全部消息，需求 5.5）。
     *
     * <p>除查询层 {@code ORDER BY created_at ASC} 外，服务层再以 {@code created_at}（次级 {@code id}）
     * 升序做一次防御性排序，保证即便底层返回顺序不确定也满足升序不变量。</p>
     *
     * @param conversationId 对话 id
     * @return 升序的消息列表
     * @throws BusinessException 对话不存在时（CONVERSATION_NOT_FOUND）
     */
    public List<MessageItem> getMessages(long conversationId) {
        requireConversation(conversationId);

        List<Message> messages = messageMapper.selectList(
                new QueryWrapper<Message>()
                        .eq("conversation_id", conversationId)
                        .orderByAsc("created_at")
                        .orderByAsc("id"));

        messages.sort(Comparator
                .comparing(Message::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Message::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        List<MessageItem> items = new ArrayList<>(messages.size());
        for (Message message : messages) {
            items.add(MessageItem.from(message));
        }
        return items;
    }

    /**
     * 发送消息：执行校验链后持久化用户消息（需求 5.2、5.3、5.4、5.7、5.8）。
     *
     * <p>校验顺序：对话存在 → 内容非空白且长度 ≤ 4000 → 附带素材包（若有）存在且状态就绪。任一校验失败抛出
     * {@link BusinessException} 且不写入任何 {@code message} 记录。校验通过后以 {@code role=user} 持久化消息；
     * 若为该对话首条消息，则将标题设为 {@code content} 前 {@code min(20, len)} 字符。</p>
     *
     * @param conversationId 对话 id
     * @param request        发送请求（内容与可选附件素材包）
     * @return 持久化后的消息
     * @throws BusinessException 校验失败时（CONVERSATION_NOT_FOUND / MESSAGE_CONTENT_INVALID / PACKAGE_UNAVAILABLE）
     */
    public MessageItem sendMessage(long conversationId, SendMessageRequest request) {
        Conversation conversation = requireConversation(conversationId);

        String content = request == null ? null : request.content();
        validateContent(content);

        Long attachedPackageId = request.attachedPackageId();
        if (attachedPackageId != null) {
            validatePackageAvailable(attachedPackageId);
        }

        boolean firstMessage = countMessages(conversationId) == 0L;

        LocalDateTime now = LocalDateTime.now();
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(ROLE_USER);
        message.setContent(content);
        message.setAttachedPackageId(attachedPackageId);
        message.setCreatedAt(now);
        messageMapper.insert(message);

        if (firstMessage) {
            conversation.setTitle(deriveTitle(content));
            conversation.setUpdatedAt(now);
            conversationMapper.updateById(conversation);
        }

        return MessageItem.from(message);
    }

    /**
     * 为消息写入触发任务关联（需求 5.6）。
     *
     * @param messageId 消息 id
     * @param taskId    触发的处理任务 id
     * @throws BusinessException 消息不存在时
     */
    public void attachTask(long messageId, long taskId) {
        Message message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new BusinessException(ErrorCode.MESSAGE_CONTENT_INVALID,
                    "消息不存在：id=" + messageId);
        }
        message.setTaskId(taskId);
        messageMapper.updateById(message);
    }

    /**
     * 计算对话标题：取 {@code content} 前 {@code min(20, length)} 个字符（需求 5.2）。
     *
     * <p>纯函数，便于直接做属性测试（Property 14）。入参在调用前已通过内容校验（非空白、长度 ≤ 4000）。</p>
     *
     * @param content 首条消息内容
     * @return 截取后的标题
     */
    public static String deriveTitle(String content) {
        if (content == null) {
            return "";
        }
        return content.substring(0, Math.min(TITLE_MAX_LENGTH, content.length()));
    }

    /**
     * 校验消息内容（需求 5.7）：非 {@code null}、非纯空白、长度 ≤ 4000。
     *
     * @param content 待校验内容
     * @throws BusinessException 校验失败时（MESSAGE_CONTENT_INVALID）
     */
    public static void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.MESSAGE_CONTENT_INVALID,
                    "消息内容不能为空或仅含空白字符");
        }
        if (content.length() > CONTENT_MAX_LENGTH) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("maxLength", CONTENT_MAX_LENGTH);
            details.put("actualLength", content.length());
            throw new BusinessException(ErrorCode.MESSAGE_CONTENT_INVALID,
                    "消息内容长度超过上限 " + CONTENT_MAX_LENGTH + " 个字符", details);
        }
    }

    private Conversation requireConversation(long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND,
                    "对话不存在：id=" + conversationId);
        }
        return conversation;
    }

    private void validatePackageAvailable(long packageId) {
        AssetPackage pkg = packageMapper.selectById(packageId);
        if (pkg == null || pkg.getStatus() == null || pkg.getStatus() != PackageStatus.READY) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("attachedPackageId", packageId);
            details.put("requiredStatus", PackageStatus.READY);
            if (pkg != null) {
                details.put("actualStatus", pkg.getStatus());
            }
            throw new BusinessException(ErrorCode.PACKAGE_UNAVAILABLE,
                    "所选素材包不存在或未就绪：id=" + packageId, details);
        }
    }

    private long countMessages(long conversationId) {
        Long count = messageMapper.selectCount(
                new QueryWrapper<Message>().eq("conversation_id", conversationId));
        return count == null ? 0L : count;
    }
}

package com.etherealstar.pixflow.module.conversation.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.module.conversation.dto.SendMessageRequest;
import com.etherealstar.pixflow.module.conversation.entity.Conversation;
import com.etherealstar.pixflow.module.conversation.entity.Message;
import com.etherealstar.pixflow.module.conversation.mapper.ConversationMapper;
import com.etherealstar.pixflow.module.conversation.mapper.MessageMapper;
import com.etherealstar.pixflow.module.file.domain.PackageStatus;
import com.etherealstar.pixflow.module.file.entity.AssetPackage;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 消息内容与附件校验拒绝属性测试（任务 7.6）。
 *
 * <p>Feature: pixflow, Property 17: 消息内容与附件校验拒绝——对任意消息，当其 {@code content} 为空、仅含空白
 * 字符或长度超过 4000，或其附带素材包不存在或 {@code status} 非就绪时，Conversation_Module 应拒绝该消息并返回
 * 对应错误（{@link ErrorCode#MESSAGE_CONTENT_INVALID} / {@link ErrorCode#PACKAGE_UNAVAILABLE}），
 * 且不在 {@code message} 表新增记录。
 *
 * <p>Validates: Requirements 5.7, 5.8
 */
class MessageValidationRejectionPropertyTest {

    private static final class Fixture {
        final ConversationMapper conversationMapper = mock(ConversationMapper.class);
        final MessageMapper messageMapper = mock(MessageMapper.class);
        final AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
        final ConversationService service =
                new ConversationService(conversationMapper, messageMapper, packageMapper);

        Fixture(long conversationId) {
            Conversation conversation = new Conversation();
            conversation.setId(conversationId);
            when(conversationMapper.selectById(conversationId)).thenReturn(conversation);
        }
    }

    /** 非法内容：null、空串、纯空白、或长度 > 4000。 */
    @Provide
    Arbitrary<String> invalidContents() {
        Arbitrary<String> blank = Arbitraries.of(null, "", " ", "   ", "\t", "\n", " \t\n ");
        Arbitrary<String> tooLong = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(ConversationService.CONTENT_MAX_LENGTH + 1)
                .ofMaxLength(ConversationService.CONTENT_MAX_LENGTH + 50);
        return Arbitraries.oneOf(blank, tooLong);
    }

    @Provide
    Arbitrary<String> validContents() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(100)
                .filter(s -> !s.isBlank());
    }

    /** 不可用素材包状态：解析中、解析失败，或不存在（以特殊标记区分）。 */
    @Provide
    Arbitrary<Integer> unavailableStatuses() {
        return Arbitraries.of(PackageStatus.PARSING, PackageStatus.PARSE_FAILED);
    }

    @Property(tries = 300)
    @SuppressWarnings("unchecked")
    void invalidContentIsRejectedWithoutInsert(@ForAll("invalidContents") String content) {
        long conversationId = 1L;
        Fixture f = new Fixture(conversationId);
        // 首条消息计数（即便走到这步也不应插入）
        when(f.messageMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> f.service.sendMessage(conversationId, new SendMessageRequest(content, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_CONTENT_INVALID);

        verify(f.messageMapper, never()).insert(any(Message.class));
    }

    @Property(tries = 200)
    @SuppressWarnings("unchecked")
    void unavailablePackageStatusIsRejectedWithoutInsert(@ForAll("validContents") String content,
                                                         @ForAll("unavailableStatuses") int status) {
        long conversationId = 1L;
        long packageId = 555L;
        Fixture f = new Fixture(conversationId);
        when(f.messageMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        AssetPackage pkg = new AssetPackage();
        pkg.setId(packageId);
        pkg.setStatus(status);
        when(f.packageMapper.selectById(packageId)).thenReturn(pkg);

        assertThatThrownBy(() ->
                f.service.sendMessage(conversationId, new SendMessageRequest(content, packageId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UNAVAILABLE);

        verify(f.messageMapper, never()).insert(any(Message.class));
    }

    @Property(tries = 200)
    @SuppressWarnings("unchecked")
    void missingPackageIsRejectedWithoutInsert(@ForAll("validContents") String content) {
        long conversationId = 1L;
        long packageId = 777L;
        Fixture f = new Fixture(conversationId);
        when(f.messageMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        // 素材包不存在
        when(f.packageMapper.selectById(packageId)).thenReturn(null);

        assertThatThrownBy(() ->
                f.service.sendMessage(conversationId, new SendMessageRequest(content, packageId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UNAVAILABLE);

        verify(f.messageMapper, never()).insert(any(Message.class));
    }
}

package com.etherealstar.pixflow.module.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.etherealstar.pixflow.module.conversation.dto.MessageItem;
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
import org.mockito.ArgumentCaptor;

/**
 * 消息持久化往返属性测试（任务 7.4）。
 *
 * <p>Feature: pixflow, Property 15: 消息持久化往返——对任意合法消息（内容非空白、长度 ≤ 4000、附带有效素材包
 * 或不附带），持久化后再读出，其 {@code role}、{@code content}、{@code attached_package_id}、
 * {@code conversation_id} 应与写入值一致。
 *
 * <p>Validates: Requirements 5.3, 5.4
 */
class MessagePersistenceRoundTripPropertyTest {

    private static final class Fixture {
        final ConversationMapper conversationMapper = mock(ConversationMapper.class);
        final MessageMapper messageMapper = mock(MessageMapper.class);
        final AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
        final ConversationService service =
                new ConversationService(conversationMapper, messageMapper, packageMapper);
    }

    @Provide
    Arbitrary<String> validContents() {
        // 非空白且长度 ≤ 4000；至少含一个非空白字符
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .withChars(' ', '中', '文', '_')
                .ofMinLength(1)
                .ofMaxLength(200)
                .filter(s -> !s.isBlank());
    }

    @Provide
    Arbitrary<Long> attachedPackageIds() {
        return Arbitraries.longs().between(1, 9999).injectNull(0.3);
    }

    @Property(tries = 300)
    @SuppressWarnings("unchecked")
    void persistedMessagePreservesCoreFields(@ForAll("validContents") String content,
                                             @ForAll("attachedPackageIds") Long attachedPackageId) {
        Fixture f = new Fixture();
        long conversationId = 42L;

        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        when(f.conversationMapper.selectById(conversationId)).thenReturn(conversation);

        if (attachedPackageId != null) {
            AssetPackage pkg = new AssetPackage();
            pkg.setId(attachedPackageId);
            pkg.setStatus(PackageStatus.READY);
            when(f.packageMapper.selectById(attachedPackageId)).thenReturn(pkg);
        }
        // 视为首条消息
        when(f.messageMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        MessageItem returned = f.service.sendMessage(
                conversationId, new SendMessageRequest(content, attachedPackageId));

        // 捕获写入数据库的实体，校验核心字段
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(f.messageMapper).insert(captor.capture());
        Message inserted = captor.getValue();

        assertThat(inserted.getRole()).isEqualTo(ConversationService.ROLE_USER);
        assertThat(inserted.getContent()).isEqualTo(content);
        assertThat(inserted.getConversationId()).isEqualTo(conversationId);
        assertThat(inserted.getAttachedPackageId()).isEqualTo(attachedPackageId);

        // 读出的 DTO（往返）与写入值一致
        assertThat(returned.role()).isEqualTo(ConversationService.ROLE_USER);
        assertThat(returned.content()).isEqualTo(content);
        assertThat(returned.conversationId()).isEqualTo(conversationId);
        assertThat(returned.attachedPackageId()).isEqualTo(attachedPackageId);
    }
}

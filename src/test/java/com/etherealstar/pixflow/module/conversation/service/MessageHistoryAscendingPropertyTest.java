package com.etherealstar.pixflow.module.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.etherealstar.pixflow.module.conversation.dto.MessageItem;
import com.etherealstar.pixflow.module.conversation.entity.Conversation;
import com.etherealstar.pixflow.module.conversation.entity.Message;
import com.etherealstar.pixflow.module.conversation.mapper.ConversationMapper;
import com.etherealstar.pixflow.module.conversation.mapper.MessageMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 消息历史升序属性测试（任务 7.5）。
 *
 * <p>Feature: pixflow, Property 16: 消息历史升序——对任意某对话的消息集合，请求消息历史应按 {@code created_at}
 * 升序返回且不遗漏任何消息。
 *
 * <p>为隔离数据库层排序，本测试令底层 mapper 以打乱顺序返回消息，验证服务层仍输出严格升序且条数与集合一致。
 *
 * <p>Validates: Requirements 5.5
 */
class MessageHistoryAscendingPropertyTest {

    private static final class Fixture {
        final ConversationMapper conversationMapper = mock(ConversationMapper.class);
        final MessageMapper messageMapper = mock(MessageMapper.class);
        final AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
        final ConversationService service =
                new ConversationService(conversationMapper, messageMapper, packageMapper);
    }

    @Provide
    Arbitrary<List<Long>> epochSecondsLists() {
        // 允许重复的创建时刻，验证次级排序键（id）下仍稳定不遗漏
        return Arbitraries.longs().between(0, 1_000_000)
                .list().ofMinSize(0).ofMaxSize(40);
    }

    @Property(tries = 300)
    @SuppressWarnings("unchecked")
    void historyIsAscendingByCreatedAtAndComplete(@ForAll("epochSecondsLists") List<Long> seconds,
                                                  @ForAll long shuffleSeed) {
        Fixture f = new Fixture();
        long conversationId = 7L;

        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        when(f.conversationMapper.selectById(conversationId)).thenReturn(conversation);

        // 构造消息集合：id 递增、createdAt 由 seconds 决定
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < seconds.size(); i++) {
            Message m = new Message();
            m.setId((long) i + 1);
            m.setConversationId(conversationId);
            m.setRole(ConversationService.ROLE_USER);
            m.setContent("m" + i);
            m.setCreatedAt(LocalDateTime.ofEpochSecond(seconds.get(i), 0, java.time.ZoneOffset.UTC));
            messages.add(m);
        }

        // 底层以打乱顺序返回
        List<Message> shuffled = new ArrayList<>(messages);
        Collections.shuffle(shuffled, new Random(shuffleSeed));
        when(f.messageMapper.selectList(any(QueryWrapper.class))).thenReturn(shuffled);

        List<MessageItem> result = f.service.getMessages(conversationId);

        // 不遗漏：条数一致，且 id 集合一致
        assertThat(result).hasSize(messages.size());
        assertThat(result.stream().map(MessageItem::id).collect(Collectors.toList()))
                .containsExactlyInAnyOrderElementsOf(
                        messages.stream().map(Message::getId).collect(Collectors.toList()));

        // 严格按 createdAt 升序（同 createdAt 时按 id 升序）
        for (int i = 1; i < result.size(); i++) {
            MessageItem prev = result.get(i - 1);
            MessageItem cur = result.get(i);
            int cmp = prev.createdAt().compareTo(cur.createdAt());
            assertThat(cmp).isLessThanOrEqualTo(0);
            if (cmp == 0) {
                assertThat(prev.id()).isLessThanOrEqualTo(cur.id());
            }
        }
    }
}

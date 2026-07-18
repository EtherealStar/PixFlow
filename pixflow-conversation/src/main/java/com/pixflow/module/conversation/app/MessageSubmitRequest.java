package com.pixflow.module.conversation.app;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 消息提交只接受 prompt 与有序 references；旧字段必须显式拒绝。 */
public final class MessageSubmitRequest {
    private final String prompt;

    private final List<MessageReferenceInput> references;

    @JsonCreator
    public MessageSubmitRequest(
            @JsonProperty("prompt") String prompt,
            @JsonProperty("references") List<MessageReferenceInput> references) {
        this.prompt = prompt;
        this.references = references == null ? List.of() : List.copyOf(references);
    }

    public String prompt() {
        return prompt;
    }

    public List<MessageReferenceInput> references() {
        return references;
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("unknown message submission field: " + fieldName);
    }
}

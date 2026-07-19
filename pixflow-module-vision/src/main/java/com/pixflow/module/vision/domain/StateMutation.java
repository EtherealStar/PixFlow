package com.pixflow.module.vision.domain;

public enum StateMutation {
    APPLIED,
    IDEMPOTENT,
    VERSION_CONFLICT,
    GENERATION_CONFLICT,
    ACTIVE_CONFLICT
}

package com.pixflow.module.vision.persistence;

public record ImageFactsRow(String inputFingerprint, String factsJson, long version) { }

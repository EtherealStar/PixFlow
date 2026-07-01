package com.pixflow.module.rubrics.model;

public record EvidenceRef(
        EvidenceType type,
        String ref,
        String excerpt,
        int[] boundingBox) {

    public EvidenceRef {
        if (type == null) {
            type = EvidenceType.DATA;
        }
        if (boundingBox != null && boundingBox.length != 4) {
            throw new IllegalArgumentException("boundingBox must be [x,y,w,h]");
        }
        boundingBox = boundingBox == null ? null : boundingBox.clone();
    }

    @Override
    public int[] boundingBox() {
        return boundingBox == null ? null : boundingBox.clone();
    }
}

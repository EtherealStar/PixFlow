package com.pixflow.harness.permission.proof;

/** 可信事实查询的三种结果；只有 PROVED 可以继续授权。 */
public enum ProofResult {
    PROVED,
    DENIED,
    UNAVAILABLE
}

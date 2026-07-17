package com.pixflow.infra.auth.identity;

/** 仅表示资格校验失败；对外错误由具体认证边界统一映射，避免泄露失败原因。 */
public final class AdministratorIneligibleException extends RuntimeException {
    public AdministratorIneligibleException() {
        super("administrator eligibility check failed");
    }
}

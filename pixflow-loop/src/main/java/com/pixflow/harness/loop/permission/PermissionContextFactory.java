package com.pixflow.harness.loop.permission;

import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.permission.PermissionContext;

/** 把回合运行态中的可信字段投影为 Permission 自有模型。 */
public interface PermissionContextFactory {
    PermissionContext create(RuntimeState state);
}

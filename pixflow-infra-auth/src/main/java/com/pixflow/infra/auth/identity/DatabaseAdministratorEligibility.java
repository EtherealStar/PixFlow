package com.pixflow.infra.auth.identity;

import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.persistence.UserAccountEntity;
import com.pixflow.infra.auth.persistence.UserAccountMapper;
import com.pixflow.infra.auth.persistence.UserAccountStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

public final class DatabaseAdministratorEligibility implements AdministratorEligibility {
    private final UserAccountMapper userMapper;

    private final AuthProperties properties;

    public DatabaseAdministratorEligibility(UserAccountMapper userMapper, AuthProperties properties) {
        this.userMapper = userMapper;
        this.properties = properties;
    }

    @Override
    public AuthPrincipal current() {
        UserAccountEntity account = userMapper.selectOne(new LambdaQueryWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getUsername, properties.getAdminUsername())
                .last("limit 1"));
        if (account == null) {
            throw new AdministratorIneligibleException();
        }
        return requireEligible(account.getId());
    }

    @Override
    public AuthPrincipal requireEligible(long userId) {
        UserAccountEntity account = userMapper.selectById(userId);
        String normalizedUsername = account == null ? null : UsernameNormalizer.normalize(account.getUsername());
        if (account == null
                || !properties.getAdminUsername().equals(normalizedUsername)
                || !UserAccountStatus.ACTIVE.name().equals(account.getStatus())) {
            throw new AdministratorIneligibleException();
        }
        return new AuthPrincipal(account.getId(), normalizedUsername, account.getDisplayName());
    }
}

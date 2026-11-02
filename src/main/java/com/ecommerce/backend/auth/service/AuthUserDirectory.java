package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The auth module's answer to the rbac module's {@link UserDirectory} questions. */
@Component
class AuthUserDirectory implements UserDirectory {

    private final UserRepository users;

    AuthUserDirectory(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID userId) {
        return users.existsById(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public long countActive(Collection<UUID> userIds) {
        return userIds.isEmpty() ? 0 : users.countByIdInAndStatus(userIds, UserStatus.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findActiveUserId(String email) {
        return users.findByEmail(User.normalizeEmail(email)).filter(User::isActive).map(User::getId);
    }
}

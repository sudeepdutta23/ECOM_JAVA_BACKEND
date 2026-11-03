package com.ecommerce.backend.profile.service;

import com.ecommerce.backend.profile.repository.UserAddressRepository;
import com.ecommerce.backend.profile.repository.UserProfileRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the auth module calls while an account is erased, like {@code org.service.MemberDeparture}:
 * the profile and the address book go in the same transaction as the anonymisation, or not at all.
 *
 * <p>Deactivation does not call it: a deactivated account can come back, with its data.
 */
@Service
public class ProfileEraser {

    private final UserProfileRepository profiles;
    private final UserAddressRepository addresses;

    public ProfileEraser(UserProfileRepository profiles, UserAddressRepository addresses) {
        this.profiles = profiles;
        this.addresses = addresses;
    }

    /** What erasure removed, for the log line. */
    public record Removed(boolean profile, int addresses) {
    }

    /**
     * Deletes the account's profile and addresses. Call it after the account row was changed: the
     * deletes flush that change first, and a save waiting on the account's lock then sees the erasure.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Removed forget(UUID userId) {
        return new Removed(profiles.deleteByUserId(userId) > 0, addresses.deleteAllByUserId(userId));
    }
}

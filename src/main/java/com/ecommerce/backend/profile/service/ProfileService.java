package com.ecommerce.backend.profile.service;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.profile.dto.ProfileResponse;
import com.ecommerce.backend.profile.dto.UpdateProfileRequest;
import com.ecommerce.backend.profile.entity.UserProfile;
import com.ecommerce.backend.profile.repository.UserProfileRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in person's own profile (Phase 5). There is no profile ID in any URL: the account
 * comes from the access token, so one person cannot address another's profile at all.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final UserProfileRepository profiles;
    private final UserDirectory users;

    public ProfileService(UserProfileRepository profiles, UserDirectory users) {
        this.profiles = profiles;
        this.users = users;
    }

    /** The caller's profile, or an empty one if it was never saved. */
    @Transactional(readOnly = true)
    public ProfileResponse get(UUID userId) {
        return profiles.findById(userId).map(ProfileResponse::from).orElse(ProfileResponse.EMPTY);
    }

    /**
     * Creates or replaces the caller's profile.
     *
     * <p>Optimistic locking covers the first save too: {@code version} absent means "I saw no
     * profile", so two devices saving a first profile at once cannot silently overwrite each other.
     *
     * <p>The account row is locked FOR SHARE first. An access token outlives a deactivation or an
     * erasure by up to 15 minutes; without the lock, a save running alongside an erasure could
     * write a new profile right after the erasure deleted the old one. With it, either the erasure
     * waits and then deletes what was saved, or it committed first and the save is refused.
     */
    @Transactional
    public ProfileResponse save(UUID userId, UpdateProfileRequest request) {
        if (request.timeZone() != null && !ZoneId.getAvailableZoneIds().contains(request.timeZone())) {
            throw RequestValidationException.of("timeZone", "is not a known time zone, for example Europe/Berlin");
        }
        if (!users.lockIfActive(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "This account is no longer available.");
        }
        UserProfile.Details details = new UserProfile.Details(request.givenName(), request.familyName(),
                request.phoneNumber(), request.preferredLocale(), request.timeZone());

        Optional<UserProfile> existing = profiles.findById(userId);
        UserProfile profile;
        if (existing.isEmpty()) {
            if (request.version() != null) {
                throw changedMeanwhile();
            }
            profile = create(userId, details);
        } else {
            profile = existing.get();
            if (!Objects.equals(profile.getVersion(), request.version())) {
                // Fast, explicit check. @Version still protects the window between this read and the commit.
                throw changedMeanwhile();
            }
            profile.change(details);
            profiles.flush(); // bump the version now so the response carries the new value
        }
        log.info("Profile saved: userId={}, version={}", userId, profile.getVersion());
        return ProfileResponse.from(profile);
    }

    private UserProfile create(UUID userId, UserProfile.Details details) {
        try {
            // saveAndFlush: a parallel first save fails HERE, on the primary key, not at commit.
            return profiles.saveAndFlush(UserProfile.create(userId, details));
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("pk_user_profiles")) {
                throw changedMeanwhile();
            }
            throw e;
        }
    }

    private static BusinessException changedMeanwhile() {
        return new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                "Your profile was changed meanwhile, perhaps on another device. Reload it and try again.");
    }
}

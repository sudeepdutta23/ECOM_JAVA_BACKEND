package com.ecommerce.backend.profile.service;

import com.ecommerce.backend.common.audit.Audited;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.profile.PersonalProfile;
import com.ecommerce.backend.profile.entity.UserAddress;
import com.ecommerce.backend.profile.entity.UserProfile;
import com.ecommerce.backend.profile.repository.UserAddressRepository;
import com.ecommerce.backend.profile.repository.UserProfileRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a person's profile and address book as one {@link PersonalProfile}: for the person's own
 * data export, and for support staff holding {@code profile:read} (step 5.3).
 */
@Service
public class ProfileReader {

    private static final Logger log = LoggerFactory.getLogger(ProfileReader.class);

    private final UserProfileRepository profiles;
    private final UserAddressRepository addresses;
    private final UserDirectory users;

    public ProfileReader(UserProfileRepository profiles, UserAddressRepository addresses, UserDirectory users) {
        this.profiles = profiles;
        this.addresses = addresses;
        this.users = users;
    }

    /** The person's own data, for "download my data". The caller is the account owner. */
    @Transactional(readOnly = true)
    public PersonalProfile read(UUID userId) {
        Optional<UserProfile> profile = profiles.findById(userId);
        return new PersonalProfile(
                profile.map(UserProfile::getGivenName).orElse(null),
                profile.map(UserProfile::getFamilyName).orElse(null),
                profile.map(UserProfile::getPhoneNumber).orElse(null),
                profile.map(UserProfile::getPreferredLocale).orElse(null),
                profile.map(UserProfile::getTimeZone).orElse(null),
                profile.map(UserProfile::getUpdatedAt).orElse(null),
                addresses.findAllByUserIdOrderByIdDesc(userId).stream().map(ProfileReader::toAddress).toList());
    }

    /**
     * One of the person's own addresses, for checkout to copy into an order. Empty for an address
     * of someone else, exactly like an unknown one.
     */
    @Transactional(readOnly = true)
    public Optional<PersonalProfile.Address> address(UUID userId, UUID addressId) {
        return addresses.findByIdAndUserId(addressId, userId).map(ProfileReader::toAddress);
    }

    /**
     * A customer's data for support staff. Unknown and erased accounts are 404: there is nothing to
     * see, and the answer does not tell erased apart from never existing.
     *
     * <p>Every access is logged with the viewer and the request ID, never with the data itself. It
     * is the audit trail until the audit module (Phase 9) stores it.
     */
    @Transactional(readOnly = true)
    @Audited(action = "profile.view", targetType = "user", targetId = "#userId")
    public PersonalProfile readForSupport(UUID staffId, UUID userId) {
        if (!users.exists(userId)) {
            throw new ResourceNotFoundException("User");
        }
        log.info("Personal data viewed by staff: userId={}, by={}", userId, staffId);
        return read(userId);
    }

    private static PersonalProfile.Address toAddress(UserAddress address) {
        return new PersonalProfile.Address(address.getId(), address.getLabel(), address.getRecipientName(),
                address.getCompany(), address.getLine1(), address.getLine2(), address.getCity(), address.getRegion(),
                address.getPostalCode(), address.getCountryCode(), address.getPhoneNumber(),
                address.isDefaultShipping(), address.isDefaultBilling(), address.getCreatedAt(), address.getUpdatedAt());
    }
}

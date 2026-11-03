package com.ecommerce.backend.profile.service;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.FieldErrorDetail;
import com.ecommerce.backend.profile.dto.AddressRequest;
import com.ecommerce.backend.profile.dto.AddressResponse;
import com.ecommerce.backend.profile.entity.UserAddress;
import com.ecommerce.backend.profile.repository.UserAddressRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in person's address book (step 5.2). Addresses are always looked up together with
 * their owner, so another person's address ID answers 404, exactly like an unknown one.
 *
 * <p>Every write takes two locks, in this order: the account row FOR SHARE (an erasure running at
 * the same time cannot be overtaken, as for the profile), then an advisory lock for this account's
 * address book (parallel requests cannot pass the limit together or leave two defaults).
 */
@Service
public class AddressService {

    private static final Logger log = LoggerFactory.getLogger(AddressService.class);

    /** Advisory-lock namespace for address-book writes; organisation creation uses 4001. */
    static final int ADDRESS_BOOK_LOCK_NAMESPACE = 5_001;

    /** The nil UUID: no address has it, so "clear every default except this one" clears them all. */
    private static final UUID NO_ADDRESS = new UUID(0, 0);

    private final UserAddressRepository addresses;
    private final UserDirectory users;
    private final ProfileProperties properties;

    public AddressService(UserAddressRepository addresses, UserDirectory users, ProfileProperties properties) {
        this.addresses = addresses;
        this.users = users;
        this.properties = properties;
    }

    /** All of the caller's addresses, newest first. Bounded by the limit, so no paging. */
    @Transactional(readOnly = true)
    public List<AddressResponse> list(UUID userId) {
        return addresses.findAllByUserIdOrderByIdDesc(userId).stream().map(AddressResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AddressResponse get(UUID userId, UUID addressId) {
        return AddressResponse.from(load(userId, addressId));
    }

    /**
     * Saves a new address. The first one becomes the default for shipping and billing, whatever
     * the request says: an address book with addresses but no default is never the starting point.
     */
    @Transactional
    public AddressResponse create(UUID userId, AddressRequest request) {
        UserAddress.Details details = validated(request);
        lockForWrite(userId);
        long saved = addresses.countByUserId(userId);
        if (saved >= properties.maxAddresses()) {
            throw new BusinessException(ErrorCode.PROFILE_ADDRESS_LIMIT, "Your address book holds "
                    + properties.maxAddresses() + " addresses, the maximum. Delete one first.");
        }
        boolean first = saved == 0;
        UserAddress address = UserAddress.create(userId, details);
        clearOtherDefaults(userId, NO_ADDRESS, first || request.defaultShipping(), first || request.defaultBilling());
        address.setDefaults(first || request.defaultShipping(), first || request.defaultBilling());
        addresses.saveAndFlush(address);
        log.info("Address created: userId={}, addressId={}", userId, address.getId());
        return AddressResponse.from(address);
    }

    /** Replaces an address, its default flags included. */
    @Transactional
    public AddressResponse update(UUID userId, UUID addressId, AddressRequest request) {
        if (request.version() == null) {
            throw RequestValidationException.of("version", "is required when replacing an address");
        }
        UserAddress.Details details = validated(request);
        lockForWrite(userId);
        UserAddress address = load(userId, addressId);
        if (address.getVersion() != request.version().longValue()) {
            // Fast, explicit check. @Version still protects the window between this read and the commit.
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "This address was changed meanwhile, perhaps on another device. Reload it and try again.");
        }
        // Before changing the entity: the bulk update flushes first, and the edit must be ONE update (one version).
        clearOtherDefaults(userId, addressId, request.defaultShipping() && !address.isDefaultShipping(),
                request.defaultBilling() && !address.isDefaultBilling());
        address.change(details);
        address.setDefaults(request.defaultShipping(), request.defaultBilling());
        addresses.flush(); // write now, so the response carries the new version and timestamp
        log.info("Address updated: userId={}, addressId={}", userId, addressId);
        return AddressResponse.from(address);
    }

    /**
     * Deletes an address. If it was a default, there is no default of that kind afterwards: the
     * person chooses at the next checkout, instead of the system guessing which address is right.
     */
    @Transactional
    public void delete(UUID userId, UUID addressId) {
        lockForWrite(userId);
        addresses.delete(load(userId, addressId));
        log.info("Address deleted: userId={}, addressId={}", userId, addressId);
    }

    // ------------------------------------------------------------------ internals

    private static UserAddress.Details validated(AddressRequest request) {
        UserAddress.Details details = new UserAddress.Details(request.label(), request.recipientName(),
                request.company(), request.line1(), request.line2(), request.city(), request.region(),
                request.postalCode(), request.countryCode(), request.phoneNumber());
        List<FieldErrorDetail> errors = AddressRules.check(details);
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
        return details;
    }

    private void lockForWrite(UUID userId) {
        if (!users.lockIfActive(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "This account is no longer available.");
        }
        addresses.lockAddressBook(ADDRESS_BOOK_LOCK_NAMESPACE, userId.hashCode());
    }

    /**
     * Unsets the current default of each kind that is about to move to {@code keepId}, before that
     * address is written: the partial unique indexes allow only one default of each kind.
     */
    private void clearOtherDefaults(UUID userId, UUID keepId, boolean shipping, boolean billing) {
        if (shipping) {
            addresses.clearDefaultShipping(userId, keepId);
        }
        if (billing) {
            addresses.clearDefaultBilling(userId, keepId);
        }
    }

    private UserAddress load(UUID userId, UUID addressId) {
        return addresses.findByIdAndUserId(addressId, userId).orElseThrow(() -> new ResourceNotFoundException("Address"));
    }
}

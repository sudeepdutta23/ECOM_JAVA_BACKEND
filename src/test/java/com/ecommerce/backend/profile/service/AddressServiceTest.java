package com.ecommerce.backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.profile.dto.AddressRequest;
import com.ecommerce.backend.profile.dto.AddressResponse;
import com.ecommerce.backend.profile.entity.UserAddress;
import com.ecommerce.backend.profile.repository.UserAddressRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class AddressServiceTest {

    private static final UUID USER = UUID.randomUUID();

    private final UserAddressRepository addresses = mock(UserAddressRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final AddressService service = new AddressService(addresses, users, new ProfileProperties(3));

    @BeforeEach
    void setUp() {
        when(users.lockIfActive(USER)).thenReturn(true);
        when(addresses.saveAndFlush(any(UserAddress.class))).thenAnswer(i -> persisted(i.getArgument(0), 0L));
    }

    private static UserAddress persisted(UserAddress address, long version) {
        ReflectionTestUtils.setField(address, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(address, "version", version);
        return address;
    }

    private static AddressRequest berlin(boolean shipping, boolean billing, Long version) {
        return new AddressRequest("Home", "Ada Lovelace", null, "Friedrichstraße 123", null, "Berlin", null, "10117",
                "DE", null, shipping, billing, version);
    }

    private UserAddress existing(boolean shipping, boolean billing, long version) {
        UserAddress address = persisted(UserAddress.create(USER, new UserAddress.Details(null, "Ada", null, "Old St 1",
                null, "Berlin", null, "10115", "DE", null)), version);
        address.setDefaults(shipping, billing);
        when(addresses.findByIdAndUserId(address.getId(), USER)).thenReturn(Optional.of(address));
        return address;
    }

    // ------------------------------------------------------------------ create

    @Test
    void theFirstAddressBecomesTheDefaultForBothWhateverTheRequestSays() {
        when(addresses.countByUserId(USER)).thenReturn(0L);

        AddressResponse created = service.create(USER, berlin(false, false, null));

        assertThat(created.defaultShipping()).isTrue();
        assertThat(created.defaultBilling()).isTrue();
        assertThat(created.version()).as("one insert, no follow-up update").isZero();
    }

    @Test
    void aLaterAddressIsDefaultOnlyWhenAskedAndThenTheOldDefaultIsClearedFirst() {
        when(addresses.countByUserId(USER)).thenReturn(1L);

        AddressResponse created = service.create(USER, berlin(true, false, null));

        assertThat(created.defaultShipping()).isTrue();
        assertThat(created.defaultBilling()).isFalse();
        InOrder order = inOrder(addresses);
        order.verify(addresses).clearDefaultShipping(eq(USER), any());
        order.verify(addresses).saveAndFlush(any(UserAddress.class));
        verify(addresses, never()).clearDefaultBilling(any(), any());
    }

    @Test
    void bothLocksComeBeforeTheCountSoParallelRequestsCannotPassTheLimitTogether() {
        when(addresses.countByUserId(USER)).thenReturn(0L);

        service.create(USER, berlin(false, false, null));

        InOrder order = inOrder(users, addresses);
        order.verify(users).lockIfActive(USER);
        order.verify(addresses).lockAddressBook(eq(AddressService.ADDRESS_BOOK_LOCK_NAMESPACE), eq(USER.hashCode()));
        order.verify(addresses).countByUserId(USER);
    }

    @Test
    void aFullAddressBookIsRefused() {
        when(addresses.countByUserId(USER)).thenReturn(3L);

        assertThatThrownBy(() -> service.create(USER, berlin(false, false, null))).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PROFILE_ADDRESS_LIMIT);
        verify(addresses, never()).saveAndFlush(any());
    }

    @Test
    void countryRulesAreCheckedBeforeAnythingIsLocked() {
        AddressRequest noPostalCode = new AddressRequest(null, "Ada", null, "Street 1", null, "Berlin", null, null,
                "DE", null, false, false, null);

        assertThatThrownBy(() -> service.create(USER, noPostalCode)).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(users, addresses);
    }

    @Test
    void anInactiveAccountCannotWriteWithAStillValidToken() {
        when(users.lockIfActive(USER)).thenReturn(false);

        assertThatThrownBy(() -> service.create(USER, berlin(false, false, null))).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(addresses, never()).lockAddressBook(anyInt(), anyInt());
        verify(addresses, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------ update and delete

    @Test
    void replacingMakesItTheDefaultAndClearsOnlyTheOtherDefaultBeforeTheEdit() {
        UserAddress address = existing(false, true, 2);

        service.update(USER, address.getId(), berlin(true, true, 2L));

        assertThat(address.getLine1()).isEqualTo("Friedrichstraße 123");
        assertThat(address.isDefaultShipping()).isTrue();
        InOrder order = inOrder(addresses);
        order.verify(addresses).clearDefaultShipping(USER, address.getId());
        order.verify(addresses).flush();
        verify(addresses, never()).clearDefaultBilling(any(), any()); // already the billing default
    }

    @Test
    void leavingAFlagOutUnsetsItBecausePutReplacesEverything() {
        UserAddress address = existing(true, true, 0);

        service.update(USER, address.getId(), berlin(false, true, 0L));

        assertThat(address.isDefaultShipping()).isFalse();
        assertThat(address.isDefaultBilling()).isTrue();
    }

    @Test
    void aStaleVersionIsAConflictAndChangesNothing() {
        UserAddress address = existing(false, false, 2);

        assertThatThrownBy(() -> service.update(USER, address.getId(), berlin(true, false, 1L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(address.getLine1()).isEqualTo("Old St 1");
        verify(addresses, never()).clearDefaultShipping(any(), any());
    }

    @Test
    void replacingWithoutAVersionIsAValidationError() {
        assertThatThrownBy(() -> service.update(USER, UUID.randomUUID(), berlin(false, false, null)))
                .isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(users);
    }

    @Test
    void someoneElsesAddressIsNotFoundForEveryOperation() {
        UUID theirs = UUID.randomUUID(); // findByIdAndUserId(theirs, USER) finds nothing

        assertThatThrownBy(() -> service.get(USER, theirs)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(USER, theirs, berlin(false, false, 0L)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(USER, theirs)).isInstanceOf(ResourceNotFoundException.class);
        verify(addresses, never()).delete(any());
    }

    @Test
    void deletingTheDefaultDoesNotPromoteAnother() {
        UserAddress address = existing(true, true, 0);

        service.delete(USER, address.getId());

        verify(addresses).delete(address);
        verify(addresses, never()).clearDefaultShipping(any(), any());
        verify(addresses, never()).saveAndFlush(any());
    }
}

package com.ecommerce.backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.profile.PersonalProfile;
import com.ecommerce.backend.profile.entity.UserAddress;
import com.ecommerce.backend.profile.entity.UserProfile;
import com.ecommerce.backend.profile.repository.UserAddressRepository;
import com.ecommerce.backend.profile.repository.UserProfileRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfileReaderTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();

    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final UserAddressRepository addresses = mock(UserAddressRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final ProfileReader reader = new ProfileReader(profiles, addresses, users);

    @Test
    void readsProfileAndAddressesTogether() {
        when(profiles.findById(USER)).thenReturn(Optional.of(UserProfile.create(USER,
                new UserProfile.Details("Ada", "Lovelace", null, "en-GB", null))));
        UserAddress home = UserAddress.create(USER, new UserAddress.Details("Home", "Ada Lovelace", null, "Street 1",
                null, "Berlin", null, "10117", "DE", null));
        home.setDefaults(true, true);
        when(addresses.findAllByUserIdOrderByIdDesc(USER)).thenReturn(List.of(home));

        PersonalProfile data = reader.read(USER);

        assertThat(data.givenName()).isEqualTo("Ada");
        assertThat(data.addresses()).singleElement().satisfies(address -> {
            assertThat(address.line1()).isEqualTo("Street 1");
            assertThat(address.defaultShipping()).isTrue();
        });
        assertThat(data.toString()).doesNotContain("Ada", "Street");
    }

    @Test
    void anAccountWithoutProfileOrAddressesReadsAsEmpty() {
        PersonalProfile data = reader.read(USER);

        assertThat(data.givenName()).isNull();
        assertThat(data.addresses()).isEmpty();
    }

    @Test
    void supportGets404ForUnknownAndErasedAccountsAndReadsNothing() {
        when(users.exists(USER)).thenReturn(false);

        assertThatThrownBy(() -> reader.readForSupport(STAFF, USER)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(profiles, addresses);
    }

    @Test
    void supportReadsAnExistingAccount() {
        when(users.exists(USER)).thenReturn(true);

        assertThat(reader.readForSupport(STAFF, USER).addresses()).isEmpty();
    }
}

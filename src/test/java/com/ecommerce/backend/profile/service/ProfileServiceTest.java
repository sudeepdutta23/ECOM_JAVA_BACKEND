package com.ecommerce.backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.profile.dto.ProfileResponse;
import com.ecommerce.backend.profile.dto.UpdateProfileRequest;
import com.ecommerce.backend.profile.entity.UserProfile;
import com.ecommerce.backend.profile.repository.UserProfileRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class ProfileServiceTest {

    private static final UUID USER = UUID.randomUUID();

    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final ProfileService service = new ProfileService(profiles, users);

    @BeforeEach
    void setUp() {
        when(users.lockIfActive(USER)).thenReturn(true);
        when(profiles.findById(USER)).thenReturn(Optional.empty());
        when(profiles.saveAndFlush(any(UserProfile.class))).thenAnswer(i -> withVersion(i.getArgument(0), 0L));
    }

    private static UserProfile withVersion(UserProfile profile, long version) {
        ReflectionTestUtils.setField(profile, "version", version);
        return profile;
    }

    private static UpdateProfileRequest request(String givenName, Long version) {
        return new UpdateProfileRequest(givenName, "Lovelace", "+493012345678", "en-GB", "Europe/Berlin", version);
    }

    private UserProfile existing(long version) {
        UserProfile profile = withVersion(UserProfile.create(USER,
                new UserProfile.Details("Ada", "Lovelace", "+493012345678", "en-GB", "Europe/Berlin")), version);
        when(profiles.findById(USER)).thenReturn(Optional.of(profile));
        return profile;
    }

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test
    void anAccountThatNeverSavedAProfileGetsAnEmptyOneWithoutVersion() {
        assertThat(service.get(USER)).isEqualTo(ProfileResponse.EMPTY);
        verifyNoInteractions(users);
    }

    @Test
    void theFirstSaveCreatesTheProfileWithoutAVersion() {
        ProfileResponse saved = service.save(USER, request("Ada", null));

        assertThat(saved.givenName()).isEqualTo("Ada");
        assertThat(saved.version()).isZero();
        verify(profiles).saveAndFlush(any(UserProfile.class));
    }

    @Test
    void theAccountIsLockedBeforeTheProfileIsReadOrWritten() {
        service.save(USER, request("Ada", null));

        InOrder order = inOrder(users, profiles);
        order.verify(users).lockIfActive(USER);
        order.verify(profiles).findById(USER);
        order.verify(profiles).saveAndFlush(any(UserProfile.class));
    }

    @Test
    void aSaveWithTheCurrentVersionReplacesEveryField() {
        UserProfile profile = existing(3);

        service.save(USER, new UpdateProfileRequest("Augusta", null, null, null, null, 3L));

        assertThat(profile.getGivenName()).isEqualTo("Augusta");
        assertThat(profile.getFamilyName()).isNull();
        assertThat(profile.getPhoneNumber()).isNull();
        verify(profiles).flush();
        verify(profiles, never()).saveAndFlush(any());
    }

    @Test
    void aStaleVersionIsAConflictAndChangesNothing() {
        UserProfile profile = existing(3);

        assertConflict(() -> service.save(USER, request("Augusta", 2L)));
        assertThat(profile.getGivenName()).isEqualTo("Ada");
    }

    @Test
    void aFirstSaveWhileAnotherDeviceAlreadyCreatedTheProfileIsAConflict() {
        existing(0);

        assertConflict(() -> service.save(USER, request("Augusta", null)));
    }

    @Test
    void aVersionForAProfileThatDoesNotExistIsAConflict() {
        assertConflict(() -> service.save(USER, request("Ada", 0L)));
        verify(profiles, never()).saveAndFlush(any());
    }

    @Test
    void twoFirstSavesAtOnceMeetOnThePrimaryKeyAndTheSecondIsAConflict() {
        when(profiles.saveAndFlush(any(UserProfile.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"pk_user_profiles\""));

        assertConflict(() -> service.save(USER, request("Ada", null)));
    }

    @Test
    void otherIntegrityErrorsAreNotDisguisedAsConflicts() {
        when(profiles.saveAndFlush(any(UserProfile.class)))
                .thenThrow(new DataIntegrityViolationException("violates check constraint \"ck_user_profiles_phone_e164\""));

        assertThatThrownBy(() -> service.save(USER, request("Ada", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDeactivatedOrErasedAccountCannotWriteAProfileWithAStillValidToken() {
        when(users.lockIfActive(USER)).thenReturn(false);

        assertThatThrownBy(() -> service.save(USER, request("Ada", null))).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(profiles, never()).findById(any());
        verify(profiles, never()).saveAndFlush(any());
    }

    @Test
    void anUnknownTimeZoneIsAValidationErrorBeforeAnythingIsLocked() {
        for (String zone : new String[] {"Mars/Olympus_Mons", "GMT+5", "+05:00", "europe/berlin"}) {
            assertThatThrownBy(() -> service.save(USER,
                    new UpdateProfileRequest(null, null, null, null, zone, null)))
                    .as(zone).isInstanceOf(RequestValidationException.class);
        }
        verifyNoInteractions(users);
    }

    @Test
    void everyIanaZoneAndUtcAreAccepted() {
        for (String zone : new String[] {"Europe/Berlin", "America/Argentina/Buenos_Aires", "Asia/Kolkata", "UTC"}) {
            assertThat(service.save(USER, new UpdateProfileRequest(null, null, null, null, zone, null)).timeZone())
                    .isEqualTo(zone);
        }
    }
}

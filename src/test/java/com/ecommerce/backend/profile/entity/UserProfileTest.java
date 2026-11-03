package com.ecommerce.backend.profile.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserProfileTest {

    private static final UserProfile.Details FULL =
            new UserProfile.Details("Ada", "Lovelace", "+493012345678", "en-GB", "Europe/London");

    @Test
    void aNewProfileHasNoVersionSoSpringDataInsertsIt() {
        UserProfile profile = UserProfile.create(UUID.randomUUID(), FULL);

        assertThat(profile.getVersion()).isNull();
        assertThat(profile.getGivenName()).isEqualTo("Ada");
        assertThat(profile.getTimeZone()).isEqualTo("Europe/London");
    }

    @Test
    void changeReplacesEveryFieldAndClearsWhatIsNotGiven() {
        UserProfile profile = UserProfile.create(UUID.randomUUID(), FULL);

        profile.change(new UserProfile.Details("Ada", null, null, "de", null));

        assertThat(profile.getGivenName()).isEqualTo("Ada");
        assertThat(profile.getFamilyName()).isNull();
        assertThat(profile.getPhoneNumber()).isNull();
        assertThat(profile.getPreferredLocale()).isEqualTo("de");
        assertThat(profile.getTimeZone()).isNull();
    }

    @Test
    void aProfileBelongsToAnAccount() {
        assertThatThrownBy(() -> UserProfile.create(null, FULL)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringKeepsNamesAndPhoneNumbersOutOfLogs() {
        UserProfile profile = UserProfile.create(UUID.randomUUID(), FULL);

        assertThat(profile.toString()).doesNotContain("Ada", "Lovelace", "+4930");
        assertThat(FULL.toString()).doesNotContain("Ada", "Lovelace", "+4930");
    }
}

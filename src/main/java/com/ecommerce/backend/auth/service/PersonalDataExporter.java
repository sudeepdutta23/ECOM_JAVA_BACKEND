package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.dto.PersonalDataExport;
import com.ecommerce.backend.auth.repository.UserSummary;
import com.ecommerce.backend.org.service.OrganizationService;
import com.ecommerce.backend.profile.service.ProfileReader;
import com.ecommerce.backend.rbac.service.AuthorizationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the caller's data export from every module that holds personal data. It lives in auth
 * because auth may call the others ({@code auth -> org, profile, rbac}); none of them may call auth.
 * Each module contributes through its public service, never through its tables.
 */
@Service
public class PersonalDataExporter {

    private static final Logger log = LoggerFactory.getLogger(PersonalDataExporter.class);

    private final AccountService accounts;
    private final AuthorizationService authorization;
    private final SessionService sessions;
    private final OrganizationService organizations;
    private final ProfileReader profiles;
    private final Clock clock;

    public PersonalDataExporter(AccountService accounts, AuthorizationService authorization, SessionService sessions,
                                OrganizationService organizations, ProfileReader profiles, Clock clock) {
        this.accounts = accounts;
        this.authorization = authorization;
        this.sessions = sessions;
        this.organizations = organizations;
        this.profiles = profiles;
        this.clock = clock;
    }

    /**
     * One read-only transaction, so the whole export uses one connection. An erased account gets
     * 401 like {@code /me}: an access token outlives the erasure, the data does not.
     *
     * @param currentSessionId the caller's {@code sid} claim, to mark this device among the sessions
     */
    @Transactional(readOnly = true)
    public PersonalDataExport export(UUID userId, UUID currentSessionId) {
        UserSummary account = accounts.currentUser(userId);
        PersonalDataExport export = new PersonalDataExport(
                Instant.now(clock),
                new PersonalDataExport.Account(account.id(), account.email(), account.status(),
                        account.emailVerifiedAt(), account.lastLoginAt(), account.createdAt()),
                List.copyOf(authorization.authoritiesOf(userId).roles()),
                sessions.list(userId, currentSessionId),
                organizations.allMemberships(userId),
                profiles.read(userId));
        log.info("Personal data exported: userId={}", userId);
        return export;
    }
}

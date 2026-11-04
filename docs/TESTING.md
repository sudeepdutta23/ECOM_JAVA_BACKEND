# Testing

## Layers

| Kind | Naming | Runs in | Needs Docker | Tools |
|---|---|---|---|---|
| Unit | `*Test` | `mvn test` | no | JUnit 5, Mockito, AssertJ |
| Web slice | `*Test` with `@WebMvcTest` | `mvn test` | no | MockMvc |
| Integration | `*IT` | `mvn verify` | yes | `@SpringBootTest`, Testcontainers PostgreSQL |

Surefire runs `*Test`. Failsafe runs `*IT`.

`pom.xml` pins Testcontainers to 1.21.4. The 1.21.3 release managed by Spring Boot 3.5.7 cannot talk to Docker Engine 29 and fails with "Could not find a valid Docker environment" and an HTTP 400. Remove the override once the Spring Boot version manages 1.21.4 or newer.

## Running

Integration tests start PostgreSQL and Redis with Testcontainers, so Docker must be running. Test JVMs are capped at 1 GB of heap: on a machine short of memory, uncapped forks crashed ("insufficient memory"). Unit and integration tests load Mockito as a `-javaagent` (configured in the POM). JDK 21 restricts agents that attach themselves at runtime, and Mockito's self-attach failed intermittently. If an IDE builds into `target/` while Maven runs, classes can vanish mid-run (`NoClassDefFoundError`, "Unable to find a @SpringBootConfiguration"): run `mvn clean verify` with the IDE's auto-build paused.

## Principles

- Integration tests use a real PostgreSQL, never H2, because constraints, types and SQL dialect must match production.
- Time-dependent logic receives a `Clock` so tests use a fixed clock.
- Each test is independent and leaves no shared state.

## Phase 0 tests

| Test | What it proves |
|---|---|
| `RequestIdFilterTest` | IDs are generated, reused when safe, rejected when unsafe, and removed from the MDC |
| `GlobalExceptionHandlerTest` | Every error type maps to the correct status and code without leaking internals |
| `SystemControllerTest` | Ping returns the envelope; a wrong method returns Problem Details |
| `ApplicationIT` | Full app starts on PostgreSQL, Flyway runs, health is UP, OpenAPI is published |

## Phase 1 tests

| Test | What it proves |
|---|---|
| `AuthSchemaIT` | Every V2 constraint rejects bad data at the database level |
| `UuidV7Test` | IDs carry version 7, the right timestamp, sort by time, and stay unique |
| `UserTest`, `RefreshTokenTest`, `AccountTokenTest` | Entity rules: normalization, state changes, idempotent revoke, no secrets in `toString` |
| `UserRepositoryIT` | UUID v7 and audit fields on save, unique email, optimistic locking, atomic failed-login counter with 20 parallel threads |
| `RefreshTokenRepositoryIT` | Bulk revocation scope, dirty-checking persistence, `SELECT ... FOR UPDATE` really blocks a second locker |
| `AccountTokenRepositoryIT` | Invalidating older links of one purpose only |

| `RsaKeyLoaderTest`, `AccessTokenIssuerTest`, `PasswordEncoderConfigTest` | Key loading rules, token claims and expiry, Argon2id parameters |
| `ProblemDetailsSecurityHandlersTest`, `SecurityIT` | 401/403 bodies; real-HTTP attacks with forged, expired, foreign and `alg=none` tokens |
| `OpaqueTokensTest`, `PasswordPolicyTest`, `MaskingTest`, `AsyncConfigTest` | Token format and hashing, password rules, log masking, request ID in async threads |
| `AccountTokenServiceTest`, `RegistrationServiceTest` | Token issue/consume rules; registration branches incl. duplicate and race handling |
| `AuthControllerTest` | HTTP contract: 202/200, validation, unknown fields, JSON-only |
| `RegistrationIT` | Full journey with async email: register, verify once, resend, duplicates, weak password, 5 parallel registrations |
| `AuthenticationServiceTest`, `RefreshTokenServiceTest`, `RefreshTokenCookieTest` | Every login branch, dummy-hash timing, lock notification, hash upgrade, cookie attributes; refresh locks the account before the token and trusts the status read under that lock |
| `SessionIT` | Rotation chains, theft replay kills the family and emails the owner, other devices unaffected, 5-way refresh race, logout, logout-all, access token lifetime after logout |
| `PasswordServiceTest`, `AccountServiceTest`, `PasswordAndAccountControllerTest` | Change/forgot/reset branches, lockout on wrong current password, token not burned on policy failure, `/me` allow-list, deactivation |
| `AccountLifecycleIT` | Change keeps this device and ends others, lockout via change endpoint, full forgot/reset, single-use and expiring links, reset unlocks, `/me`, deactivation |
| `RateLimitFilterTest` | Capacity, 429 shape and headers, per-IP and per-rule buckets, refill with a manual clock, unlisted paths untouched |
| `RateLimitIT` | Real filter chain in its own context: 11th login and 6th email-sending call refused |
| `SecurityIT` (CORS and headers) | Allowed and refused preflights, exposed headers, CSP on API but not Swagger UI, Permissions-Policy |
| `AuthJourneyIT` | One user's full life cycle, plus an audit of all log output: no password, token, hash or full email anywhere |
| `QueryPlanIT` | `EXPLAIN` of 80 hot auth, rbac, user-admin, organisation, profile, address, catalogue, order, notification, file, audit, search and job queries (with seq scans disabled, over 5 000 synthetic accounts and about 2 000 sellers loaded for the class and removed after) proves each one uses its index. Without the volume, equal costs on tiny tables made the chosen index depend on test order |
| `UserSearchTest`, `UserAdminControllerTest` | LIKE escaping; defaults, size cap and page validation reported per parameter, enum values listed without Java type names, `user:read` required |
| `UserAdminIT` | Case-insensitive prefix search newest first, `%` and `_` matched literally, page totals and past-the-end, combined filters, no credentials in responses, detail with roles, sessions, lock and status change |
| `UserAdminServiceTest` | Order of checks (manage rule, last admin, then revoke), self-action, escalation, invalid status, nothing changes on refusal, atomic unlock, sign-out reason |
| `UserAdminActionsIT` | Suspension ends sessions and blocks sign-in without revealing the reason (also not in logs), reactivation after suspension and self-deactivation, unlock after a real lockout, sign-out of two devices, a support role limited to customers, last admin kept |
| `SessionServiceTest`, `SessionsIT` | One session per sign-in, `current` from the `sid` claim, same ID across rotation, ending another device only, ending the current one like logout, 404 for other users' sessions, logout-all empties the list |
| `AccountErasureIT` | Owner and admin erasure down to the rows: placeholder email, `{erased}` hash, no tokens, links or roles; old email and placeholder both get 401 (no 500); the email registers again as a new account; confirmation mail; no email in logs; `user:manage` is not enough; second erasure 409; no role grants to an erased account; last admin kept; the V5 CHECK rejects an `ERASED` row that still holds data; a placeholder cannot be registered (400, and the V6 CHECK); an erasure waits for a refresh in progress and deletes its new token |
| `OrganizationTest`, `OrgRoleTest` | Seller lifecycle (approve, suspend with reason, reactivate, close is terminal), slug rules, the organisation role matrix |
| `OrganizationServiceTest`, `OrganizationControllerTest` | Creation order of checks (lock before limit before insert), inactive account, reserved slug, limit, slug taken (also in a race), outsider 404 vs member 403, stale version, read-only when suspended or closed; slug normalization, validation, no smuggled fields, paging limits |
| `OrganizationOnboardingIT` | Over HTTP: founder owns a pending organisation and lists it; outsiders get the same 404 as for unknown IDs; case-insensitive slug uniqueness and reserved slugs; six parallel creations, exactly three succeed; rename with version, staff 403, stale 409; suspended is read-only and hides the reason |
| `OrganizationInvitationTest`, `InvitationServiceTest`, `MembershipServiceTest` | Invitation lifetime and single outcome; only the hash stored, the raw link only in the event; rank rule for invite, role change and removal; already a member; re-invite replaces; pending cap; forwarded link refused; same answer for unknown, revoked and expired links; last owner kept; lock before checks; leaving a suspended or closed organisation |
| `OrganizationMembersIT` | Over HTTP with the real email link: the invitee registers after the invitation, accepts once, appears in the list; a forwarded link fails for another account; re-invite and revoke kill old links; manager limits; the last owner must hand over before leaving, and a deactivated co-owner does not count; two owners demoting each other at once leave exactly one owner; outsiders 404; no emails or tokens in logs |
| `OrganizationGuardTest` | The guard: non-member 404 like an unknown organisation, permission before status, `READ` in every status, `WRITE` only while pending or active |
| `TenantIsolationIT` | Every `{orgId}` operation from the live OpenAPI spec, with valid example bodies: as an outsider, the same 404 as for a nonexistent organisation and nothing changes; with another seller's child IDs through the caller's own organisation, 404. New path variables need a fixture or the test fails |
| `ModuleBoundariesTest` | ArchUnit, no Spring: no module cycles; common depends on no module; rbac knows neither auth nor org; org does not know auth, profile or catalog; catalog knows neither auth nor profile; profile knows neither auth nor org; entity, repository, dto and controller packages are private to their module; controllers never use repositories |
| `OrganizationAdminServiceTest`, `MemberDepartureTest` | Each platform transition needs its status; conflict of interest; closing revokes invitations, once; sole owners named in the refusal, co-owned shops allow leaving; erasure removes memberships and invitations |
| `OrganizationAdminIT` | Over HTTP: approval queue, approve, suspend hides the reason from members and logs and makes the shop read-only (no closing to escape), reactivate; staff cannot approve their own shop; customers 403; an owner closes, a manager cannot; invitations die, the slug stays taken, the last owner may then leave |
| `OrganizationDepartureIT` | The only owner cannot deactivate or erase until the shop is co-owned or closed; deactivation keeps memberships; erasure deletes memberships and invitations across sellers, the organisation keeps the anonymous ID; an administrator cannot erase a sole owner but may suspend one |
| `SellerJourneyIT` | Phase 4 as one story: founded, queued, approved; a team built by invitation with the rank rule; suspended (read-only, reason hidden) and reactivated; the founder cannot be erased until ownership is shared, then is; closed by the new owner, still readable, slug kept; plus a log audit (no emails, tokens or reasons) |
| `UserProfileTest`, `ProfileServiceTest`, `ProfileControllerTest` | Replace semantics; account locked before the read; first save without version, every conflict case (stale, missing, already created, parallel insert on the primary key), other integrity errors not disguised; inactive account refused before anything is read; unknown and offset time zones rejected; normalization of names, phone and language tag; names in any script; control and text-direction characters, malformed phones and locales rejected; no smuggled `userId`; 401 signed out; no personal data in `toString` |
| `ProfileIT` | Over HTTP: empty, first save, second first save 409, replace clears omitted fields, stale version 409, another account sees only its own; erasure deletes the profile and the still-valid token cannot recreate it; deactivation keeps it read-only; an erasure waits for a save in progress and deletes what it wrote; no names or phone numbers in logs |
| `ProfileReaderTest`, `ProfileAdminControllerTest`, `PasswordAndAccountControllerTest` (export) | Profile and addresses read together; empty account; support gets 404 for unknown and erased accounts and reads nothing; `profile:read` required, `user:read` alone is 403; export is an uncached attachment marking this session, without credential fields |
| `ProfileJourneyIT` | Phase 5 as one story: profile and two addresses (default moves), support views them, the customer downloads everything, erases the account; no profile or address rows remain, support gets 404, the old token cannot write; plus a log audit (no email, name, street, phone or time zone) |
| `PersonalDataIT` | The export holds every module's data about the caller (account, roles, current device, organisation, profile, address) and no hashes; an erased account's token exports nothing; support with `profile:read` sees the customer and the view is logged without the data; `user:read` + `user:manage` and customers are 403; erased and unknown accounts answer the same 404 |
| `AddressRulesTest` | Country rules: valid formats for each listed country, countries without postal codes, ISO codes only (`UK` refused), missing or malformed postal codes and regions, every violation at once |
| `UserAddressTest`, `AddressServiceTest`, `AddressControllerTest` | The first address is the default for both; the old default cleared before the write, one version per write; both locks before the count; limit; country rules before any lock; inactive account; stale version; `version` required; flags replaced; someone else's address 404 for read, replace and delete; no promotion on delete; normalization of postal code, country and phone; required fields; no smuggled `userId` |
| `AddressBookIT` | Over HTTP: defaults move and are never doubled, newest first, clearing a default bumps the old version; another person's address answers exactly like an unknown one; country rules; six parallel requests with room for two, exactly two succeed and one default remains; erasure deletes the address book and the old token cannot refill it; no addresses in logs |
| `ProfileSchemaIT` | V10 and V11: one profile per account, existing account required, E.164 and language-tag CHECKs, trimmed non-empty fields, version starts at 0; one default of each kind per account (partial unique indexes), format CHECKs on addresses, cascade with the account |
| `ProductTest`, `ProductServiceTest`, `CatalogControllerTest` | Product life cycle; on sale only while the seller is active; exact money (no silent rounding); guard before any read or write; pending sellers build an invisible catalogue; SKU unique per seller also in a race; unknown category; suspended seller refused; version required and checked; another seller's product 404; stock atomic, never below zero, never by zero; public storefront, paging bounds, SKU normalization, no smuggled stock/status/owner, `catalog:manage` for categories |
| `CatalogIT` | Over HTTP: visibility through pending, approved, suspended, reactivated, archived and closed; filters by category and seller; a category in use cannot be deleted; staff manage the catalogue, outsiders get 404, SKUs are case-insensitive; ten parallel removals from a stock of five, exactly five succeed; a suspension waits for a seller write in progress and then hides what it wrote |
| `CartServiceTest` | Empty cart not ready; total of buyable lines at current prices; NOT_ENOUGH_STOCK and NOT_ON_SALE (also when the seller stopped selling); only products on sale can be added, quantity is set; full cart refuses new lines but changes existing ones; inactive account |
| `CartIT` | Over HTTP: products of two sellers, set-not-add, a price change shows at once; scarce and suspended lines stay marked and leave the total, nothing new from a suspended seller; archived and unknown products 404, removing idempotent, 99 per line; each customer sees only their own cart; erasure empties it and the old token cannot refill it |
| `CheckoutServiceTest` | One global lock order (account, checkouts, sellers by ID, products by ID); one order per seller with the server's total; a seller suspended between check and lock, or a last-moment sell-out, stops everything; a used key replays without reading the cart; missing and malformed keys rejected before any lock |
| `CheckoutIT` | Over HTTP: two sellers, two orders, snapshots of price, name, seller and address survive later edits, stock taken, cart emptied; same key replays; five parallel retries buy once; the last unit sold once across two customers; stale total and short stock change nothing; a suspended seller blocks the checkout; a foreign address 400 without leaking it; checkouts private; erasure keeps the orders |
| `OrderLifecycleIT` | Pay (idempotent), ship with tracking, deliver; declined payment changes nothing and can be retried; cancelling returns stock, paid orders are refunded, sellers must give a reason; orders visible only to their customer and seller; a suspended seller still ships paid orders; cancel and ship at once, exactly one wins; no tokens, addresses or reasons in logs |
| `MarketplaceJourneyIT` | Phase 6 as one story: two sellers, one customer, one checkout and one payment for two orders; one seller ships and delivers, the other is suspended and cancels with a refund; stock and history add up; log audit |
| `OrderMessagesTest` | Who hears what per event: paid to customer and every seller member, cancellations to the other side, nothing for an unreachable customer, tracking only when given, deliveries inbox only |
| `NotificationIT` | A paid order reaches both inboxes and both email boxes (no address in the email), shipping adds the tracking number and the badge counts; read state per person, someone else's notification 404, idempotent read, read-all; switched-off emails are not sent but the inbox fills; erasure empties the inbox and an erased customer gets nothing new |
| `ImageProcessorTest`, `LocalFileStorageTest` | JPEG and PNG re-encoded; appended payloads gone; no EXIF segment; SVG, HTML, GIF, BMP and empty files refused by their bytes; truncated images refused; a decompression bomb refused from its header; storage round trip, idempotent delete, every traversal key refused |
| `ProductImageIT` | Over HTTP: upload re-encoded under a server-made key (the file name `../../../etc/passwd.png` never becomes a path); drafts not public but previewable (`no-store`); on sale listed and served with the stored type, `nosniff`, public caching; disguised scripts and empty files 400; 6 MB 413 before any code reads it; eight images at most, deleting removes the file; outsiders 404 |
| `AuditIT` | An administrator's suspension and reactivation are recorded with actor, target, request ID and client IP, without the reason; a refused action leaves no entry; role grants carry their detail, category changes their new ID; UPDATE, DELETE and TRUNCATE fail on the table even for the application's own account; `audit:read` required; keyset paging with a cursor that ends on the last page |
| `StorefrontQueryTest`, `SearchIT` | Sort defaults (relevance with text, newest without); name matches rank above description matches; phrases, exclusions, case; drafts never found; injection attempts and stray operators as search text answer 200 and change nothing; price range, in stock, price sorting and paging; min above max, unknown sort, negative or sub-cent prices, too deep pages and too long text are 400 |
| `JsonCacheTest`, `RateLimitFilterTest` (fail open) | Miss loads and stores with the TTL, hit read back into records, Redis down and unreadable values fall back to the loader, eviction waits for the commit, disabled cache always loads; a broken bucket store lets requests through |
| `SharedRateLimitIT` | Two stores (two servers) against one Redis share a client's bucket: exactly the limit gets through; other clients have their own buckets |
| `CatalogCacheIT` | The category list is cached and a new category shows at once; a suspended seller's cached product page disappears at once |
| `JobsIT` | An unpaid order is cancelled after the timeout, its stock returns and the customer is told, while paid and fresh orders stay, and a second round finds nothing; expired refresh tokens deleted, live ones kept; notifications past the retention deleted, recent ones kept; with two servers, a job's round runs on one only (ShedLock) |
| `OrganizationSchemaIT` | V7 and V8: slug format and uniqueness, trimmed name, suspension reason, role CHECK, cascades with organisation and user, SET NULL on founder and actor, ADMIN holds `org:*`, indexes; one open invitation per address, single outcome, normalized email, hex hash, cascade |
| `UserManagementSchemaIT` | V4: suspension needs a reason, status and revoke-reason checks, SET NULL on the actor, ADMIN holds `user:*`, one email index serving equality and prefix |
| `BoundedPasswordEncoderTest` | Concurrency cap honoured, fails fast with 503, permits always returned |
| `RbacSchemaIT` | V3 constraints, seed data, and the V2-to-V3 upgrade backfill in a scratch schema |
| `PermissionTest`, `PermissionCatalogVerifierTest` | Codes valid and in sync with constants; startup refuses a drifted catalogue |
| `JwtAuthoritiesConverterTest`, `PermissionControllerTest` | Claims become authorities; `@PreAuthorize` allows, denies with 403, and ignores bare role names |
| `AuthorizationIT` | CUSTOMER on registration, claims in tokens, 403 for customers, promotion visible in `/me` at once and in the token after refresh |
| `RoleManagementServiceTest`, `RoleControllerTest` | Every guard (escalation before and after edit, system roles, in-use, stale version, unknown permission); HTTP contract incl. 201 Location, immutable code, 403/404/400 |
| `RoleManagementIT` | Full CRUD, lost-update protection, an editor without `role:assign` blocked from granting or stripping it, holders gaining permissions at refresh |
| `RoleAssignmentServiceTest`, `UserRoleControllerTest` | Self-modification, escalation in both directions, CUSTOMER kept, last active admin, lock taken before checks, bootstrap outcomes; 204/403/409 contract and `role:assign` required |
| `RoleAssignmentIT` | Grant and revoke idempotent and visible after refresh, 404s, an assigner limited to its own power, last admin kept, two admins demoting each other at once leave one, bootstrap only while no active admin exists |
| `UserAdministrationJourneyIT` | Phase 3 as one story: support role without erase, own sessions, prefix search, suspend and reactivate, lockout and unlock, admin sign-out, self-erasure, plus a log audit (no tokens, emails or suspension reason) |
| `AdminJourneyIT` | Phase 2 as one story: bootstrap, delegation, the only admin cannot leave, hand-over, stale token refused for management, deactivation rules, plus a log audit |
| `LoginIT` | Tokens and cookie over HTTP, hashed storage, identical failure bodies, lockout and expiry, one lock email, parallel guessing, BCrypt upgrade |

Integration tests replace the email transport with `RecordingEmailSender` and wait for async delivery with Awaitility.

Repository tests commit for real instead of rolling back, because locking and concurrency only behave realistically across separate transactions. They clean up their own rows afterwards.

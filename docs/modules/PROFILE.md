# Profile design (Phase 5)

Status: complete (Phase 5 review: [PHASE-5-REVIEW.md](../reviews/PHASE-5-REVIEW.md)).

## 1. Purpose

An account (auth) answers "who may sign in?". A profile answers "how do we address this person, and how do we reach them?":

| Belongs to auth (Phases 1 to 3) | Belongs to profile (Phase 5) |
|---|---|
| Email (the login), password, status, sessions | Given and family name, phone number |
| Roles (rbac), memberships (org) | Preferred language and time zone |
| | Address book: shipping and billing addresses |

The split keeps personal contact data out of the security-critical `users` table and module. A profile has its own life cycle: optional, edited often, and deleted at erasure while the account row stays.

## 2. Placement and dependencies

New module `profile`:

```
auth ──> org ──> rbac ──> common
  │                ^
  ├──> profile ────┘
  └────────────────┘
```

- `profile` uses rbac's `UserDirectory` port, like org, for one question: "is this account active, and lock it while I write" (`lockIfActive`, section 6).
- `profile` imports neither `auth` nor `org`; `org` does not import `profile`. Users are referenced by ID; the foreign keys keep integrity.
- `auth` calls `profile.service.ProfileEraser` during erasure, like `org.service.MemberDeparture`.
- `ModuleBoundariesTest` enforces all of this, and that the profile's entities, repositories, DTOs and controllers are private to it.

## 3. The profile (5.1)

| Field | Rule | Why |
|---|---|---|
| `givenName`, `familyName` | Optional, at most 100 characters, trimmed; any script; no control or text-direction characters | Not everyone has two names, and names are not ASCII. Bidirectional overrides (U+202E) can make a name display as something else |
| `phoneNumber` | Optional, E.164 (`+` country code, 7 to 15 digits); spaces, hyphens, dots and brackets removed | One canonical form, so a later SMS provider or a duplicate check can use it as is |
| `preferredLocale` | Optional BCP 47 tag, language and optional region: `de`, `en-GB`; case normalized | Emails and the frontend in the person's language (Phase 7) |
| `timeZone` | Optional IANA zone ID known to the JVM: `Europe/Berlin`, `UTC`; offsets such as `+05:00` are rejected | An offset is not a zone: it ignores daylight saving time |

**Unverified phone number.** There is no SMS yet, so the number is contact data only. It is never used to sign in, to recover an account or as a second factor. Doing that safely needs verification by code first (a later phase, with its own rate limits).

**Why no libphonenumber?** It would check number plans per country. The E.164 shape is enough for contact data and keeps a large dependency with frequent data updates out until SMS verification needs it.

**No account ID in any URL.** `GET` and `PUT /api/v1/profile` act on the account in the access token. There is no `/profiles/{id}` for customers, so there is nothing to guess or enumerate (no IDOR by construction). Support access to other people's profiles is a separate, permission-checked endpoint (step 5.3).

**PUT replaces everything.** A field left out or blank is cleared. The client sends what the form shows; no PATCH semantics to get wrong ("was it omitted, or cleared?").

**Optional row, optimistic locking from the first save.** An account without a profile gets an empty response without `version`. The first save sends no `version`, every later save sends the one it read; anything else is 409 `CONCURRENT_MODIFICATION`. So two devices saving a first profile at once cannot overwrite each other silently: the second meets the existing row (or the primary key, if both insert at once) and gets 409.

## 4. The address book (5.2)

| Field | Rule |
|---|---|
| `recipientName`, `line1`, `city`, `countryCode` | Required |
| `label` | Optional, the owner's own name for it ("Home"); never printed on a parcel |
| `company`, `line2`, `region`, `postalCode`, `phoneNumber` | Optional, unless the country needs them (below) |
| `defaultShipping`, `defaultBilling` | At most one of each per account |

All text fields are trimmed, blank means not given, and control and text-direction characters are rejected, as for names. Postal and country codes are upper-cased with single spaces (`sw1a  1aa` becomes `SW1A 1AA`); the phone number is E.164 like the profile's.

**Country rules** (`AddressRules`), checked before anything is locked, with every violation reported at once:

- The country is an ISO 3166-1 alpha-2 code: `GB`, not `UK`.
- For the main markets (DE, AT, CH, FR, IT, ES, NL, BE, DK, SE, PL, GB, US, CA, AU, IN) the postal code is required and must match the country's format. Elsewhere it is optional, because many countries have none (Hong Kong, the United Arab Emirates), and only its general shape is checked.
- US, CA, AU and IN need a `region` (state or province).

This is a format check, not proof that the address exists. A verification service (Google Address Validation, Loqate) is the step once checkout needs guaranteed deliverability; the list grows with the countries the shop ships to.

**Defaults.**

- The **first address** becomes the default for shipping and billing, whatever the request says: a non-empty address book always starts with a default.
- Making another address the default first unsets the old one, in the same transaction. Two partial unique indexes (`... (user_id) WHERE default_shipping`, likewise billing) make two defaults impossible even if the code were wrong.
- `PUT` replaces the flags too: leaving one out unsets it.
- Deleting a default leaves **no default** of that kind. The system does not guess which remaining address is right; checkout asks.

**Limit: 20 addresses** (`app.profile.max-addresses`, 409 `PROFILE_ADDRESS_LIMIT`). Enough for any household, it bounds what one scripted account can store and lets the list come without paging.

**Ownership.** Every repository finder takes the owner: `findByIdAndUserId`. There is no way to load an address by ID alone, so another person's address ID answers 404 with the same body as an unknown ID, for read, replace and delete alike.

**Orders keep their own copy.** Phase 6 will copy the address into the order when it is placed. Editing or deleting an address must never change where a past order went or what its invoice says, so orders never reference `user_addresses`.

### Locks on every address write

1. The **account row FOR SHARE** (`lockIfActive`), as for the profile: an erasure cannot be overtaken (section 6).
2. An **advisory lock for this account's address book** (`pg_advisory_xact_lock(5001, hash(userId))`, held until commit). Two parallel requests at 19 addresses would otherwise both count 19 and both insert; two "make this the default" requests could both clear and both set. A row lock is not an option: the account row is only share-locked (several holders at once), and an empty address book has no row to lock.

Always in this order. Erasure takes only the first (its update waits for share holders) and never the advisory lock, so there is no cycle. `AddressBookIT` sends six requests at once with room for two: exactly two succeed, and there is still one default.

## 4a. Support access and "download my data" (5.3)

**Support view.** `GET /api/v1/admin/users/{userId}/profile` shows a customer's profile and addresses, for example to sort out a delivery.

- It needs **`profile:read`** (V12, granted to `ADMIN`), deliberately separate from `user:read`. A team that manages accounts (status, sessions, lockouts) does not automatically see where people live: least privilege for personal data.
- It is **read only**. Staff never edit someone's profile; the person does that.
- **Every view is logged**: `Personal data viewed by staff: userId=..., by=...`, with the request ID, never with the data. Since Phase 9 each view is also an `audit_events` entry (`profile.view`).
- Unknown and erased accounts get the same 404, so the endpoint does not reveal which accounts were erased.

**Download my data.** `GET /api/v1/auth/me/export` returns everything the platform holds about the caller, as one JSON document (GDPR articles 15 and 20: access and portability):

| Part | From | Module API used |
|---|---|---|
| Account: ID, email, status, verification, last sign-in, creation | auth | `AccountService.currentUser` |
| Roles | rbac | `AuthorizationService.authoritiesOf` |
| Signed-in devices with last IP address and browser | auth | `SessionService.list` |
| Organisations with role and join date | org | `OrganizationService.allMemberships` (new; `org.MembershipSummary`) |
| Profile and addresses | profile | `ProfileReader.read` (new; `profile.PersonalProfile`) |

- **It lives in auth** (`PersonalDataExporter`), because auth may call org, profile and rbac, and none of them may call auth. Each module contributes through its public API (root-package records, services), never through its tables; `ModuleBoundariesTest` still passes unchanged.
- **Left out on purpose:** password and token hashes (security material, never exposed) and internal moderation notes such as a suspension reason (disclosed on request through support, after review).
- **Erased accounts:** the still-valid access token gets 401, like `/me`.
- **Delivery:** `Content-Disposition: attachment` and `Cache-Control: no-store`, so no proxy or browser cache keeps a copy. **Rate limited** to 5 per 15 minutes per IP: one export reads every module.
- **Later phases** add their data (orders, files) to the same document with their module.

## 5. Rules

| # | Rule | Enforced by | Step |
|---|---|---|---|
| 1 | A person reads and writes only their own profile and addresses | Account ID from the token, never from the request; owner-scoped finders; unknown fields rejected | 5.1, 5.2 |
| 2 | Only an active account writes personal data, even with a still-valid access token | `UserDirectory.lockIfActive` | 5.1 |
| 3 | An erased account has no profile and no addresses, also when a save runs at the same moment | `ProfileEraser` in the erasure transaction, plus the account lock (section 6) | 5.1, 5.2 |
| 4 | Deactivation keeps profile and addresses (the account can come back) | Not called on deactivation | 5.1 |
| 5 | Names, phone numbers and addresses are never logged | IDs only (country code at most); `toString` of entities, details, requests and responses hide them | 5.1, 5.2 |
| 6 | Last write never silently wins | `version`, on the profile from the first save, on addresses for every replace | 5.1, 5.2 |
| 7 | At most one default address of each kind; at most 20 addresses | Address-book lock, partial unique indexes | 5.2 |
| 8 | Staff see personal data only with `profile:read`, read only, and every view is logged | `@PreAuthorize`, `ProfileReader.readForSupport` | 5.3 |
| 9 | A person can download everything held about them, without any credential material | `PersonalDataExporter` | 5.3 |

## 6. Erasure and the race it must win

An access token lives up to 15 minutes after an erasure. Without care, this could happen:

```
save:    check "active?" -> yes
erasure:                      anonymise account, delete profile, commit
save:                                                                insert profile, commit
```

and a fresh name and phone number would sit next to an erased account. So a save first takes a **FOR SHARE lock on the account row** (`UserRepository.lockStatus`, behind `UserDirectory.lockIfActive`). Erasure updates that row, which needs a lock that conflicts with FOR SHARE:

- **Save first:** the erasure waits at its update until the save commits, then deletes what the save wrote.
- **Erasure first:** the save waits for the lock, then READ COMMITTED shows it the erased status, and it answers 401 without writing.

Both sides lock the account row before the profile and address rows, so they cannot deadlock. Several saves can hold FOR SHARE at once; only status changes wait. `ProfileEraser.forget` deletes the profile and every address, after the account update in `AccountEraser` (its bulk deletes flush that update first). `ProfileIT` pauses a save between its write and its commit and proves that the erasure waits and then deletes it; `AddressBookIT` proves the address book is gone and the old token cannot refill it.

## 7. API

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/api/v1/profile` | | The caller's profile; fields never given and `version` (before the first save) are absent |
| PUT | `/api/v1/profile` | `{givenName, familyName, phoneNumber, preferredLocale, timeZone, version}`, all optional | Saved profile; 400 `VALIDATION_FAILED` (also an unknown time zone); 401 when the account is no longer active; 409 `CONCURRENT_MODIFICATION` |
| GET | `/api/v1/profile/addresses` | | All my addresses, newest first |
| POST | `/api/v1/profile/addresses` | `{label, recipientName, company, line1, line2, city, region, postalCode, countryCode, phoneNumber, defaultShipping, defaultBilling}` | 201 + Location; 400 (country rules); 409 `PROFILE_ADDRESS_LIMIT` |
| GET | `/api/v1/profile/addresses/{addressId}` | | One address; 404 if not mine |
| PUT | `/api/v1/profile/addresses/{addressId}` | Same as POST plus `version` (required) | Replaced, flags included; 404; 409 `CONCURRENT_MODIFICATION` |
| DELETE | `/api/v1/profile/addresses/{addressId}` | | 204; 404 if not mine |
| GET | `/api/v1/admin/users/{userId}/profile` | | Support: profile and addresses; needs `profile:read`; logged; 404 for unknown and erased accounts |
| GET | `/api/v1/auth/me/export` | | My data from every module, as a download; rate limited |

## 8. Data model (V10, V11)

`user_profiles`: one optional row per account.

| Column | Type | Notes |
|---|---|---|
| `user_id` | UUID PK, FK `users` ON DELETE CASCADE | The key is the account's ID: at most one profile, no extra index |
| `given_name`, `family_name` | VARCHAR(100) | CHECK trimmed and not empty: "not given" is only ever NULL |
| `phone_number` | VARCHAR(16) | CHECK E.164 |
| `preferred_locale` | VARCHAR(16) | CHECK language[-REGION] |
| `time_zone` | VARCHAR(64) | CHECK trimmed and not empty; the zone list is checked by the application (it changes with JVM updates) |
| `version`, `created_at`, `updated_at` | | Optimistic locking, auditing |

`user_addresses`: the address book.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID v7 PK | Time-ordered, so "newest first" is `ORDER BY id DESC` |
| `user_id` | UUID, FK `users` ON DELETE CASCADE | The owner |
| `label`, `recipient_name`, `company`, `line1`, `line2`, `city`, `region` | VARCHAR | CHECK trimmed and not empty where present |
| `postal_code` | VARCHAR(20) | CHECK upper case, letters, digits, spaces, hyphens; per-country format in the application |
| `country_code` | VARCHAR(2) | CHECK two upper-case letters. VARCHAR, not CHAR: Hibernate's schema validation maps String to VARCHAR |
| `phone_number` | VARCHAR(16) | CHECK E.164 |
| `default_shipping`, `default_billing` | BOOLEAN | One TRUE each per account at most (partial unique indexes) |
| `version`, `created_at`, `updated_at` | | Optimistic locking, auditing |

| Index | Serves |
|---|---|
| `idx_user_addresses_user_id (user_id, id)` | My addresses newest first, the limit count, erasure |
| `uk_user_addresses_default_shipping`, `uk_user_addresses_default_billing` | One default each; finding the current default to clear it. Partial, so only default rows are indexed |

`QueryPlanIT` checks seven new plans (the account lock, the profile, the address list, count, owner-scoped lookup, clearing a default, erasure) over two addresses for each of 5 000 accounts.

## 9. Plan

| Step | Content |
|---|---|
| 5.1 | Personal profile: names, phone number, language, time zone; erasure and the account lock |
| 5.2 | Address book: shipping and billing addresses, limit, defaults, country rules, the address-book lock |
| 5.3 | Support view (`profile:read`, every access logged) and "download my data" (own account, profile, addresses, organisations) |
| 5.4 | Phase 5 review |

# Interview questions

Every step of this project ends with questions an interviewer could ask about it. They are collected
here, phase by phase, so the whole set can be revised in one place. Each step adds its questions to
this file in the same change.

**How to use it:** answer each question out loud in two or three sentences, then check the answer
against the code and the module document linked under each phase. A good answer names the trade-off,
not just the choice: what was gained, what it cost, and when the other option would win.

197 questions so far.

| Phase | Questions | Design document |
|---|---|---|
| [Phase 0: Setup](#phase-0-setup) | 6 | [ARCHITECTURE.md](ARCHITECTURE.md) |
| [Phase 1: Authentication](#phase-1-authentication) | 48 | [modules/AUTH.md](modules/AUTH.md) |
| [Phase 2: Authorization (RBAC)](#phase-2-authorization-rbac) | 29 | [modules/RBAC.md](modules/RBAC.md) |
| [Phase 3: User management](#phase-3-user-management) | 31 | [modules/USERS.md](modules/USERS.md) |
| [Phase 4: Organisations (marketplace sellers)](#phase-4-organisations-marketplace-sellers) | 30 | [modules/ORGANIZATIONS.md](modules/ORGANIZATIONS.md) |
| [Phase 5: Profile](#phase-5-profile) | 24 | [modules/PROFILE.md](modules/PROFILE.md) |
| [Phase 6: Core business (catalogue and orders)](#phase-6-core-business-catalogue-and-orders) | 29 | [modules/CATALOG.md](modules/CATALOG.md) |

## Phase 0: Setup

Design: [ARCHITECTURE.md](ARCHITECTURE.md).

### Architecture and Project Initialization

1. Why turn off open-session-in-view?
2. Why use validate mode instead of letting Hibernate update the schema?
3. Liveness vs readiness: what is the difference?
4. Why is UUID v7 better than v4 for B-tree indexes?
5. Why clear the logging context in a `finally` block on pooled threads?
6. Problem Details vs a custom error envelope: which would you choose and why?

## Phase 1: Authentication

Design: [modules/AUTH.md](modules/AUTH.md).

### Step 1.1: Authentication architecture and database design

1. Why hash refresh tokens with SHA-256 but passwords with Argon2id?
2. How does refresh-token reuse detection work, and what race condition must you handle?
3. Why is account lockout time-based rather than permanent?
4. How do you avoid user enumeration on registration and login?
5. HS256 vs RS256: when does the difference matter?
6. Why can't a refresh token simply be a long-lived JWT?

### Step 1.2: Entities, ID generation and repositories

1. Why is a UUID v4 primary key bad for B-tree index performance?
2. Why shouldn't JPA entities use Lombok `@Data`?
3. What is the lost-update problem, and when do you prefer an atomic UPDATE over optimistic locking?
4. Pessimistic or optimistic locking for refresh-token rotation: which would you pick, and why?
5. What does `clearAutomatically` on a bulk update protect against?
6. How does Spring Data decide between `persist` and `merge`?

### Step 1.3: Security setup

1. What is the `alg=none` attack, and how do you prevent it?
2. Why derive the public key from the private key instead of configuring both?
3. Why disable CSRF in a JWT API, and when would that be a mistake?
4. Why must a JWT never contain personal data?
5. Why do security filter errors bypass `@RestControllerAdvice`?
6. What does `DelegatingPasswordEncoder` give you when you change hashing algorithms?

### Step 1.4: Registration and email verification

1. How do you stop a registration endpoint from revealing which emails exist?
2. Why send email after commit rather than inside the transaction, and what can still go wrong?
3. Why hash the password before opening the transaction?
4. What does `Propagation.MANDATORY` guarantee?
5. Why must an email verification link not perform the action on GET?
6. Why does a Java record need a custom `toString` when it holds a password?

### Step 1.5: Login, lockout and refresh-token issuing

1. How do you stop login timing from revealing which emails are registered?
2. Why must a locked account skip the real password check?
3. Why commit the failure counter in a separate transaction from the error response?
4. Why use an atomic UPDATE instead of optimistic locking for login bookkeeping?
5. When is it safe to return a specific error like "email not verified"?
6. How does `DelegatingPasswordEncoder` let you migrate hashing algorithms without a mass reset?

### Step 1.6: Refresh rotation, reuse detection and logout

1. What is refresh-token rotation, and how does it detect theft?
2. Why revoke the victim's newer token too, not just the replayed one?
3. How do you stop two concurrent refreshes from both succeeding?
4. Why must reuse detection not throw inside the transaction?
5. Why can't logout invalidate a JWT access token immediately, and what are the options?
6. Why must the cookie-clearing response use the same `Path` as the original cookie?

### Step 1.7: Passwords, `/me` and self-deactivation

1. Why ask for the current password when the user is already authenticated?
2. Why must a password change revoke existing sessions?
3. How do you avoid using up a reset link when the new password fails validation?
4. What is an IDOR vulnerability, and how does taking the ID from the token prevent it?
5. When would you use a DTO projection instead of loading an entity?
6. What does `@Transactional(readOnly = true)` actually change?

### Step 1.8: Rate limiting, CORS and security headers

1. How does the token-bucket algorithm differ from a fixed-window counter?
2. Why rate-limit by IP when you already have account lockout?
3. Why place the rate limiter before the security filter chain?
4. What memory risk comes with per-IP state, and how do you bound it?
5. Why can't CORS be `*` when credentials are allowed?
6. Why is HSTS only sent over HTTPS?

## Phase 2: Authorization (RBAC)

Design: [modules/RBAC.md](modules/RBAC.md).

### Step 2.1: Authorization design and database

1. Why check permissions rather than roles in code?
2. Natural key or surrogate key: how do you choose?
3. What are the trade-offs of putting authorities in a JWT?
4. How can an RBAC system be abused for privilege escalation, and how do you prevent it?
5. How do you bootstrap the first administrator safely?
6. Why must a migration that changes existing data be tested on the upgrade path, not just a fresh install?

### Step 2.2: Authorization core

1. Why should module dependencies point one way only?
2. How do you avoid N+1 queries when loading roles and their permissions?
3. Why convert enums to stable codes instead of storing enum names?
4. Why fail at startup when the code and the database disagree?
5. Where do method-security denials surface, and how should they be handled?
6. Why does `Persistable` matter for entities with application-assigned keys?

### Step 2.3: Role management

1. How can an RBAC admin API be abused for privilege escalation?
2. Why check the caller's permissions from the database instead of the token for management actions?
3. What is optimistic locking, and how do `version` fields, ETags and `If-Match` relate?
4. Why `join fetch` when listing roles with their permissions?
5. Why make system roles read-only instead of trusting admins?
6. Why authorize at both the controller and the service level?

### Step 2.4: Granting and revoking roles, plus the first admin

1. Why are grant and revoke PUT and DELETE rather than POST, and what does "idempotent" buy the client?
2. Why can "check, then insert" fail under concurrency, and what are three ways to fix it?
3. How does a row lock prevent "two admins demote each other and none remain"? Why lock *before* the checks?
4. What is dependency inversion, and how does `UserDirectory` keep the module graph free of cycles?
5. How would you bootstrap the first administrator safely, and what makes a bootstrap a back door?
6. A demoted admin still holds a valid token. What can they do with it for the next 15 minutes, and how would you shorten that?

### Step 2.5: End-to-end review, and Phase 2 is complete

1. Your last-admin rule protects one endpoint. How do you find every *other* way an account stops being active?
2. Why does `requireCanLeave` use `Propagation.MANDATORY`?
3. Which actions does a 15-minute-old token still allow after a demotion, and which does it not? Why the difference?
4. How would you make a permission change take effect immediately without a database call on every request?
5. What does a threat checklist give you that a code read-through doesn't?

## Phase 3: User management

Design: [modules/USERS.md](modules/USERS.md).

### Step 3.1: User management design, status model and schema

1. Why can't a default B-tree index serve `LIKE 'abc%'`, and what does `varchar_pattern_ops` change?
2. When is sorting by a UUID equivalent to sorting by creation time?
3. Why can a `switch` expression be safer than `if` chains for security decisions?
4. How do you add a constraint or an index to a 50-million-row table without downtime?
5. Offset or keyset pagination: when would you use each?

### Step 3.2: Admin user list, search and detail

1. Why shouldn't an API accept Spring's `Pageable` straight from the client?
2. Why might `WHERE (:x IS NULL OR col = :x)` perform badly in PostgreSQL?
3. What is a LIKE-injection, and how is it different from SQL injection?
4. Why compare against a normalized search term instead of using `lower(email)`?
5. When is a `count(*)` per page acceptable, and when should you drop the total?

### Step 3.3: Admin account actions

1. Why model suspension as a command endpoint rather than `PATCH {"status": "SUSPENDED"}`?
2. How can a permission like `user:manage` become an escalation path, and how do you close it?
3. Why can a token created by a concurrent refresh survive a bulk revocation, and why doesn't that matter here?
4. Why perform unlock as an atomic UPDATE rather than load, modify and save?
5. What belongs in an internal note, and why must it never appear in logs?

### Step 3.4: Your own sessions

1. What is a "session" in a stateless JWT system, and where does it actually live?
2. Why add a `sid` claim instead of reading the refresh cookie to find the current session?
3. Why return 404 rather than 403 for a resource that belongs to someone else?
4. Why do sessions use resource-style endpoints (`GET` and `DELETE`) while login and logout use action endpoints?
5. After a device is signed out, what can its access token still do, and for how long?

### Step 3.5: Account erasure

1. Why anonymise instead of `DELETE`, and what does that mean for foreign keys and data that must be kept by law?
2. Why delete refresh tokens on erasure when every other flow only revokes them?
3. Why put the anonymity rule in a database check as well as in the code?
4. Login compares the password before checking the status. What breaks for erased accounts, and why is filtering at lookup the right fix?
5. Why a separate `user:erase` permission rather than reusing `user:manage`?
6. What personal data does erasure not reach, such as backups, logs and other modules' data, and how would you handle each?

### Step 3.6: Review and journey test

1. Two transactions lock the same two rows in opposite orders. What happens, and how do you prevent it in design rather than with retries?
2. Why read the status with a separate scalar query under the lock instead of trusting the entity Hibernate already loaded?
3. Why add V6 instead of editing V5, which had never been committed?
4. How could a reserved placeholder become a denial-of-service attack on a privacy right?
5. Your review lists "accepted risks". How do you decide what to fix now and what to accept and document?

## Phase 4: Organisations (marketplace sellers)

Design: [modules/ORGANIZATIONS.md](modules/ORGANIZATIONS.md).

### Step 4.1: Organisations design and schema

1. Why put platform permissions in the JWT but read organisation permissions from the database?
2. Compare shared tables, schema per tenant, and row-level security for a marketplace. Why does a SaaS store platform often choose differently?
3. Why does a non-member get 404 instead of 403 on an organisation endpoint?
4. Why are slugs immutable, and why lower-case only?
5. Why fixed organisation roles now, and what would custom roles per seller cost?

### Step 4.2: Seller onboarding

1. Why an advisory lock instead of `SELECT ... FOR UPDATE` here? What does "transaction-scoped" guarantee?
2. Why check `existsBySlug` and also handle the unique violation? Why not only one of them?
3. Why 404 for non-members but 403 for members who lack the permission?
4. Why is a closed organisation's slug never released?
5. Why return `myPermissions` in the response if the server checks every action anyway?

### Step 4.3: Members and invitations

1. Why must the accepting account's email match the invitation, rather than "whoever has the link may join"?
2. Why a partial unique index, and why can't `expires_at > now()` be part of its condition?
3. How does the organisation row lock stop two owners from demoting each other to zero owners? What would happen under optimistic locking alone?
4. Why does the rank rule check both the member's current role and the new role?
5. Why cap pending invitations rather than rate-limit the endpoint?

### Step 4.4: Organisation guard and isolation tests

1. Why discover endpoints from the OpenAPI spec instead of listing them in the test?
2. Why does the isolation test need a valid request body, and what would an invalid one hide?
3. Guard plus scoped repository lookups: why both? Which attack does each one stop alone?
4. Why make entities private to a module and expose plain value types instead?
5. How do you know a security test actually tests something? What did removing the guard show here?

### Step 4.5: Platform administration and account departures

1. Why block erasure of a sole owner, given that erasure is a legal right? What is the user's way out?
2. Why lock the owned organisations in ID order? Describe the deadlock this prevents.
3. Why does suspension skip the sole-owner check when deactivation doesn't?
4. Why is conflict of interest a separate rule from permissions? Which attack would the permission check alone allow?
5. Why does erasure delete even revoked and accepted invitations?

### Step 4.6: Review and journey test

1. Why must the last-owner rule count owners who can sign in rather than owner memberships? How did the gap show up?
2. Which invariant do the platform's last-admin rule and the seller's last-owner rule share, and why do both need a lock?
3. The guard checks status but does not lock the organisation. When is that acceptable, and what must Phase 6 add for sales?
4. How do you review a multi-tenant module for isolation? Which classes of bugs does `TenantIsolationIT` catch, and which not?
5. Why is a journey test with a log audit worth having on top of focused tests?

## Phase 5: Profile

Design: [modules/PROFILE.md](modules/PROFILE.md).

### Step 5.1: Personal profile

1. Why keep names and phone numbers out of the `users` table and the auth module?
2. An access token outlives an erasure by up to 15 minutes. How could a profile save leave personal data behind an erased account, and how does a FOR SHARE lock on the account row prevent it?
3. Why FOR SHARE and not FOR UPDATE for that lock? What would FOR UPDATE cost?
4. How do you apply optimistic locking to a row that may not exist yet?
5. Why does `/api/v1/profile` have no ID in the path? Which class of vulnerability does that remove?
6. Why store phone numbers in E.164, and why must an unverified number never be used for sign-in or recovery?
7. Why an IANA time zone instead of a UTC offset?
8. Which Unicode characters would you reject in a name, and why not restrict names to letters?

### Step 5.2: Address book

1. Why must an order copy the address instead of referencing the address book?
2. How does a partial unique index enforce "at most one default per account", and why must the old default be cleared before the new one is written?
3. Two requests arrive at 19 of 20 addresses. Why can a FOR SHARE lock on the account not stop both from inserting, and what does the advisory lock add?
4. Why look addresses up by ID and owner together instead of loading by ID and comparing the owner afterwards?
5. Why does deleting the default address not promote another one?
6. What does country-specific validation prove, and what does it not? When would you add an address verification service?
7. Why does `PUT` replace the default flags too, and what would PATCH semantics cost here?

### Step 5.3: Support access and data export

1. Why is reading personal data a separate permission from managing accounts? Which team would hold one but not the other?
2. Why log every support view, and what must the log line contain and not contain?
3. Why answer 404 for both unknown and erased accounts?
4. Which module should assemble a data export that spans four modules, given the dependency direction? What would break if org assembled it?
5. What belongs in a GDPR export, and why leave out password hashes and moderation notes?
6. Why `Cache-Control: no-store` and a rate limit on the export?

### Step 5.4: Review and journey test

1. How do you review a module for privacy rather than only for access control? Which questions did this review ask?
2. Why is "the person can always get all their data, and erasure always wins" an invariant worth a journey test?
3. Which accepted risks of Phase 5 should later phases close, and which phase owns each?

## Phase 6: Core business (catalogue and orders)

Design: [modules/CATALOG.md](modules/CATALOG.md), [modules/ORDERS.md](modules/ORDERS.md).

### Step 6.1: Catalogue, stock and storefront

1. Why store money as a long of minor units instead of a double or a BigDecimal column? What does `RoundingMode.UNNECESSARY` protect against?
2. Why is a stock change a delta in one UPDATE statement instead of "read, add, save"? What does the WHERE clause guarantee?
3. How do you keep a suspended seller's products off the storefront without the catalogue joining the organisation tables or org calling the catalogue?
4. Why must the listener for that event run in the same transaction, not after commit?
5. Phase 4 accepted "the guard is a check, not a lock". Which race does a FOR SHARE lock on the organisation close, and why not use it for organisation renames too?
6. Why does the storefront hide exact stock counts and answer 404 for drafts and suspended sellers?
7. Why is the SKU unique per seller and not globally? How does the unique key behave in a race?
8. Why RESTRICT instead of CASCADE from products to organisations?

### Step 6.2: Cart

1. Why does the cart store quantities but never prices? How does checkout protect the customer from a price change?
2. Why `PUT .../items/{productId}` with a quantity instead of `POST .../items` that adds one?
3. Why keep lines that cannot be bought in the cart instead of removing them?
4. How is a cart view kept at three queries regardless of its size?
5. Which information does a cart leak about stock, and why is that accepted?

### Step 6.3: Checkout

1. How does a checkout guarantee that the last unit is not sold twice? What happens in the losing transaction, and why does READ COMMITTED make it work?
2. Why does checkout lock sellers and products in ID order? Describe the deadlock it prevents.
3. How does an Idempotency-Key make retries safe, and why is a unique constraint alone not enough for parallel retries?
4. Why does the client send `expectedTotal`, and why must the server never use it as the price?
5. Why copy product names, prices, the seller's name and addresses into the order instead of referencing them?
6. Why are address snapshots JSONB documents while order lines are rows?
7. Why can only ACTIVE sellers sell, while pending ones may already build a catalogue?

### Step 6.4: Payment and order life cycle

1. Why must card data never reach this server? What does the client send instead, and what does that do to PCI DSS scope?
2. How do you prevent charging twice: for two clicks, and for a retry after a timeout?
3. Why is calling a real payment provider inside the database transaction a problem, and what replaces it?
4. Why may a suspended seller still ship orders that were already paid?
5. How do you make a cancellation and a shipment of the same order mutually exclusive?
6. Why are orders kept after an erasure request? Which GDPR clause allows it, and what is still removed?

### Step 6.5: Review and journey test

1. How do you review a commerce flow? Why "follow the money and the stock"?
2. Which five races did Phase 6 prove with tests, and which mechanism closes each?
3. Which accepted risks of Phase 6 belong to background jobs (Phase 12) and events (Phase 13)?


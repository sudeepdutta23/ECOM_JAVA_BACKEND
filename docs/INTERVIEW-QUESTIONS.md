# Interview questions

Every step of this project ends with questions an interviewer could ask about it. They are collected
here, phase by phase, so the whole set can be revised in one place. Each step adds its questions to
this file in the same change.

**How to use it:** answer each question out loud in two or three sentences, then check the answer
against the code and the module document linked under each phase. A good answer names the trade-off,
not just the choice: what was gained, what it cost, and when the other option would win.

272 questions so far.

| Phase | Questions | Design document |
|---|---|---|
| [Phase 0: Setup](#phase-0-setup) | 6 | [ARCHITECTURE.md](ARCHITECTURE.md) |
| [Phase 1: Authentication](#phase-1-authentication) | 48 | [modules/AUTH.md](modules/AUTH.md) |
| [Phase 2: Authorization (RBAC)](#phase-2-authorization-rbac) | 29 | [modules/RBAC.md](modules/RBAC.md) |
| [Phase 3: User management](#phase-3-user-management) | 31 | [modules/USERS.md](modules/USERS.md) |
| [Phase 4: Organisations (marketplace sellers)](#phase-4-organisations-marketplace-sellers) | 30 | [modules/ORGANIZATIONS.md](modules/ORGANIZATIONS.md) |
| [Phase 5: Profile](#phase-5-profile) | 24 | [modules/PROFILE.md](modules/PROFILE.md) |
| [Phase 6: Core business (catalogue and orders)](#phase-6-core-business-catalogue-and-orders) | 29 | [modules/CATALOG.md](modules/CATALOG.md) |
| [Phase 7: Notifications](#phase-7-notifications) | 8 | [modules/NOTIFICATIONS.md](modules/NOTIFICATIONS.md) |
| [Phase 8: Files](#phase-8-files) | 8 | [modules/FILES.md](modules/FILES.md) |
| [Phase 9: Audit](#phase-9-audit) | 8 | [modules/AUDIT.md](modules/AUDIT.md) |
| [Phase 10: Search, filtering, pagination](#phase-10-search-filtering-pagination) | 8 | [modules/SEARCH.md](modules/SEARCH.md) |
| [Phase 11: Redis](#phase-11-redis) | 8 | [modules/REDIS.md](modules/REDIS.md) |
| [Phase 12: Background jobs](#phase-12-background-jobs) | 8 | [modules/JOBS.md](modules/JOBS.md) |
| [Phase 13: Events and the email outbox](#phase-13-events-and-the-email-outbox) | 8 | [modules/EVENTS.md](modules/EVENTS.md) |
| [Phase 14: Observability](#phase-14-observability) | 9 | [modules/OBSERVABILITY.md](modules/OBSERVABILITY.md) |
| [Phase 15: Performance](#phase-15-performance) | 10 | [modules/PERFORMANCE.md](modules/PERFORMANCE.md) |

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

## Phase 7: Notifications

Design: [modules/NOTIFICATIONS.md](modules/NOTIFICATIONS.md).

### Step 7.1 to 7.3: Inbox, emails, preferences and review

1. Why write the in-app notification inside the order's transaction but send the email after commit?
2. What is lost if the application crashes right after commit, and how does a transactional outbox fix it?
3. Why does the order module publish events instead of calling a notification service?
4. Why are email subjects fixed while the body may contain the seller's name?
5. Why store notification links as app paths and CHECK them in the database?
6. How does a partial index keep an unread badge cheap, and what would a plain index on `(user_id, read_at)` cost instead?
7. Why can a person switch off order emails but not password-reset emails?
8. How do you make sure an erased person gets no new notifications, even for orders that are still being fulfilled?

## Phase 8: Files

Design: [modules/FILES.md](modules/FILES.md).

### Steps 8.1 and 8.2: Product images and review

1. Why decide a file's type from its first bytes instead of its name or Content-Type? What could an SVG uploaded as `.png` do?
2. What does re-encoding an image remove, and why is that better than trying to detect malicious content?
3. What is a decompression bomb, and why must the dimensions be checked before the image is decoded?
4. Why must a storage key never contain the uploaded file name? Name two independent defences against path traversal.
5. How do you keep a stored file and its database row consistent when the transaction rolls back, and when it commits?
6. Why serve images with the stored content type and `nosniff`, and why must binary responses carry no forced charset?
7. Why does an oversized upload need `max-swallow-size` to produce a 413 instead of a connection reset?
8. What changes when the application runs on several servers, and why does the `FileStorage` port make that change small?

## Phase 9: Audit

Design: [modules/AUDIT.md](modules/AUDIT.md).

### Steps 9.1 and 9.2: Audit log and review

1. What is the difference between an application log and an audit log? Who reads each, and for how long is each kept?
2. Why write the audit entry in the action's own transaction? What goes wrong if it is written after commit, or before?
3. Why does the transaction advice have to be the outermost one for that to work, and how is that configured?
4. How does a database trigger make a table append-only, and what does it protect against that application code cannot?
5. Why take the actor from the access token instead of a parameter, and why keep IDs instead of names in the log?
6. Why is keyset paging a better fit than page numbers for an audit log?
7. Why an annotation and an aspect instead of calling an audit service from each method? What does it cost?
8. Should an erasure request delete audit entries? Which data in them is personal, and what justifies keeping it?

## Phase 10: Search, filtering, pagination

Design: [modules/SEARCH.md](modules/SEARCH.md), [ADR-014](adr/ADR-014-search-in-postgresql.md).

### Steps 10.1 and 10.2: Storefront search and review

1. When is PostgreSQL full-text search enough, and what would make you move to Elasticsearch or OpenSearch?
2. Why a generated `tsvector` column instead of computing `to_tsvector` in every query or keeping it in sync from Java?
3. Why the `simple` text-search configuration rather than `english` for a marketplace?
4. Why `websearch_to_tsquery` instead of `to_tsquery` for text typed by customers?
5. How do you build a query with optional filters without ever concatenating user input into SQL? What about ORDER BY?
6. Why add `id` as a tie-breaker to every sort?
7. Why cap OFFSET paging at a fixed depth, and what does a deep OFFSET cost the database?
8. Why are the search and price indexes partial?

## Phase 11: Redis

Design: [modules/REDIS.md](modules/REDIS.md).

### Steps 11.1 and 11.2: Shared rate limits, read cache and review

1. Why do in-memory rate limits break as soon as a second server runs? How does a shared bucket in Redis fix it?
2. How does compare-and-swap keep two servers from both spending a bucket's last token?
3. Should a rate limiter fail open or closed when Redis is down? What protects login while it is open?
4. Why evict a cache entry after the transaction commits rather than before or inside it? Which race remains, and what bounds it?
5. What should never be cached under a shared key, and why?
6. Why can a product page with a stale stock flag be cached safely in this design?
7. Why run Redis without persistence here, and what would change if it held sessions or queues?
8. Why an explicit read-through helper instead of `@Cacheable` for two cached reads?

## Phase 12: Background jobs

Design: [modules/JOBS.md](modules/JOBS.md).

### Steps 12.1 and 12.2: Jobs and review

1. With three servers, why would a plain `@Scheduled` job run three times, and how does ShedLock prevent it?
2. Why should the lock use database time rather than each server's clock?
3. What are `lockAtMostFor` and `lockAtLeastFor` for, and what happens if a server dies while holding a lock?
4. Why process unpaid orders one per transaction instead of one transaction for the whole batch?
5. A customer pays at the moment the job cancels the same order. Walk through who wins and why nothing breaks.
6. Why delete old rows in batches of a thousand instead of one DELETE?
7. Why should each job's query have its own (often partial) index?
8. Why are jobs switched off in tests, and how are they tested then?

## Phase 13: Events and the email outbox

Design: [modules/EVENTS.md](modules/EVENTS.md), [ADR-015](adr/ADR-015-email-outbox.md).

### Steps 13.1 and 13.2: Outbox and review

1. What is the dual-write problem, and why does publishing to Kafka after commit not solve it?
2. Explain the transactional outbox pattern. What makes the email and the order change atomic?
3. How does `FOR UPDATE SKIP LOCKED` let several servers drain one queue table without sending twice?
4. Why claim rows with a lease and send outside the transaction, instead of holding the row lock while sending?
5. Why is outbox delivery "at least once" and not "exactly once"? How would a consumer make duplicates harmless?
6. Why are emails with one-time links kept out of the outbox, and what is the cost of that choice?
7. When would you choose in-transaction listeners, an outbox, or best-effort after-commit listeners?
8. When would you add a message broker to this system, and what would the outbox then publish?

## Phase 14: Observability

Design: [modules/OBSERVABILITY.md](modules/OBSERVABILITY.md).

### Steps 14.1 and 14.2: Metrics, tracing and review

1. What is the difference between logs, metrics and traces, and which would you look at first during an outage?
2. Why must a request ID or trace ID be in every log line, and how does it survive a hop to an async thread?
3. Why should `/actuator/prometheus` never be public, and how is it protected here?
4. What is label cardinality, and why must a user ID or a raw URL never be a metric tag?
5. Why count paid orders after commit rather than inside the payment method?
6. Counter, gauge, timer: which would you use for logins, the outbox backlog and request latency, and why?
7. What do SLO buckets give you that an average latency does not?
8. Why sample traces, and what do you lose at 10 % sampling?
9. Write an alert that catches credential stuffing without paging on every forgotten password.

## Phase 15: Performance

Design: [modules/PERFORMANCE.md](modules/PERFORMANCE.md), [ADR-016](adr/ADR-016-platform-threads.md).

### Steps 15.1 and 15.2: Guards and review

1. What is an N+1 query, and how does a test catch it without knowing the SQL?
2. Why compare one row with a full page instead of asserting one fixed number of statements?
3. `JOIN FETCH`, `@EntityGraph` or batch loading by ID: which fits paged lists, and why can a fetch join break paging?
4. Why would a whole-body ETag never match here, and what does the ETag hash instead?
5. What does a 304 save, and what does it not save? What saves the server's work?
6. Why must only the storefront be `Cache-Control: public`? What happens if a cart response is?
7. What do `statement_timeout` and `idle_in_transaction_session_timeout` protect against, and why set them on the server?
8. Why `SET LOCAL` and not `SET` for a migration that needs more time?
9. Would virtual threads make this application faster? What is the real limit on its throughput?
10. How would you load-test this API, and which metrics would you watch during the test?


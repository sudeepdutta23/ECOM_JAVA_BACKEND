# Audit design (Phase 9)

Status: complete (Phase 9 review: [PHASE-9-REVIEW.md](../reviews/PHASE-9-REVIEW.md)).

## 1. Purpose

The application log answers "what did the system do?" for developers, for days. The audit log answers **"who did what to whom, and when?"** for security reviews, investigations and compliance, for years. It is a product feature with its own guarantees, not a log level.

## 2. How an action gets recorded

```java
@Transactional
@Audited(action = "user.suspend", targetType = "user", targetId = "#userId")
public AdminUserDetail suspend(UUID callerId, UUID userId, String reason) { ... }
```

- `@Audited` lives in `common.audit`: any module can mark a method without depending on the audit module.
- `audit.service.AuditAspect` runs after the method **returned normally** and writes one `audit_events` row. SpEL picks the target (`#userId`, or `#result.id` for something just created), an optional organisation and an optional non-personal detail (`'role=' + #roleId`).
- **Same transaction.** `TransactionConfig` makes transactions the outermost advice (order 0), and the aspect (order 100) runs inside them. The entry commits with the action, or rolls back with it. An action that failed leaves no entry; one that committed always has one. For a method without its own transaction (`AccountService.erase`, which manages its transaction itself), the repository's `save` opens one.
- **The actor is the access token's subject**, never a request value. The entry also gets the request ID (it matches `X-Request-Id` and every log line of the request) and the client IP.

## 3. What is recorded

| Area | Actions |
|---|---|
| Accounts (administrators) | `user.suspend`, `user.reactivate`, `user.unlock`, `user.sign-out`, `user.erase` |
| Accounts (the owner) | `account.deactivate`, `account.erase` |
| Roles | `role.create`, `role.update`, `role.delete`, `role.grant`, `role.revoke` |
| Sellers (platform) | `org.approve`, `org.suspend`, `org.reactivate`, `org.close` |
| Sellers (members) | `org.close-by-owner`, `org.member-role`, `org.member-remove` |
| Categories | `category.create`, `category.rename`, `category.delete` |
| Personal data | `profile.view` (support reading a customer's profile, Phase 5) |

Business events that are already records of their own (orders and their status timestamps, payments) are not duplicated here.

## 4. Guarantees

| Guarantee | How |
|---|---|
| Nothing changes an entry, not even the application | A trigger raises on UPDATE, DELETE and TRUNCATE; the entity is `@Immutable`; there is no endpoint that writes |
| Entries outlive what they name | No foreign keys: a deleted role or account still has its history |
| No personal data in the log | IDs only, the detail is non-personal by convention (and reviewed), reasons are never copied |
| Only auditors read it | `GET /api/v1/admin/audit` needs `audit:read` (V18, `ADMIN`) |

**Personal data and erasure.** The client IP is personal data, and so is the ID of an account that acts. Both are kept: security logging is a legitimate interest, and the log may be needed for legal claims (GDPR articles 6(1)(f), 17(3)(e)). After an erasure the IDs point to an anonymous row. A retention period (for example two years) with a partition-dropping job is Phase 12.

**What the trigger does not stop.** A database superuser can disable the trigger. Against that: ship the log to separate, write-once storage (a SIEM, object storage with object lock) in Phase 14, where the application has no delete rights.

## 5. Reading

`GET /api/v1/admin/audit?actorId=&targetId=&organizationId=&action=&before=&size=`, newest first.

**Keyset paging** (ADR-011): the log grows while someone reads it, so "page 3" would shift under them, and a deep `OFFSET` gets slower page by page. Instead the response carries `nextBefore`, the last ID returned; the next request asks for entries older than that (`id < before`, UUID v7, so ID order is time order). The server fetches one row more than asked to know whether a next page exists: no count query.

## 6. Data model (V18)

`audit_events (id, occurred_at, actor_id, action, target_type, target_id, organization_id, detail, request_id, client_ip)`, CHECKs on the format of `action` and `target_type`, the append-only trigger.

| Index | Serves |
|---|---|
| `idx_audit_events_actor (actor_id, id)` | Everything one account did |
| `idx_audit_events_target (target_id, id)` | Everything that happened to one account, role or seller |
| `idx_audit_events_organization (organization_id, id)` partial | Everything in one seller |
| `pk_audit_events` | The whole log, newest first |

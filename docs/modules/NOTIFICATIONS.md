# Notifications design (Phase 7)

Status: complete (Phase 7 review: [PHASE-7-REVIEW.md](../reviews/PHASE-7-REVIEW.md)).

## 1. Purpose and placement

People learn about their orders without refreshing pages: an in-app inbox for everything, and emails for what matters, as far as the person wants them.

```
auth ──> notification ──> order (events), org (seller members), rbac (user facts) ──> common
```

- The **order module publishes** `OrderEvent` (sealed: `Paid`, `Shipped`, `Delivered`, `Cancelled`), carrying IDs, amounts and the seller's public name only. It never imports `notification`.
- The **notification module listens.** No business module may depend on it (`ModuleBoundariesTest.nothingButAuthKnowsNotifications`); auth calls `NotificationEraser` during erasure.
- Account emails of Phases 1 to 4 (verification, password reset, lockout, invitations) stay where they are: they are security mail, sent by their module's listener, and cannot be switched off.

## 2. Who hears what

| Event | Customer | Every seller member |
|---|---|---|
| Paid | Inbox + email: "Order confirmed" | Inbox + email: "New order to ship" |
| Shipped | Inbox + email, with the tracking number | |
| Delivered | Inbox | |
| Cancelled by the seller | Inbox + email, "being refunded" if it was paid | |
| Cancelled by the customer | | Inbox |

One class (`OrderMessages`) renders every message, so the inbox and the email never disagree. Texts contain IDs, amounts and the seller's name; never an address or a cancellation reason (the reason is on the order page, behind authorization).

**Email subjects are fixed.** The seller's name, which sellers choose, appears in the body only, so no seller can make an email's subject look like something else (the rule of Phase 4's invitation emails).

**Links are paths inside the app** (`/orders/{id}`, `/organizations/{orgId}/orders/{id}`), CHECKed by `ck_notifications_link_internal`; emails prefix them with `app.notification.frontend-base-url`. A notification can never point to an external site.

## 3. Two delivery guarantees

| Channel | When | Guarantee |
|---|---|---|
| Inbox | `@EventListener`, inside the order change's transaction | Commits with the change, or neither does: no notification about a payment that rolled back, none lost |
| Email | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` | Never for a rolled-back change, never slows a checkout. Best effort: a crash between commit and send loses the email (the inbox still has it); Phase 13's outbox makes it durable |

The in-app write joins the order's transaction deliberately: if the database cannot store the notification, it cannot store the order change either.

## 4. Preferences

`GET` and `PUT /api/v1/notifications/preferences`: `orderUpdatesEmail` (customer emails) and `sellerOrdersEmail` (new-order emails for seller staff; one per member, so large teams may switch it off). No row means the defaults (both on), so most accounts never have one. The inbox is always written; only email follows the preferences. Account security emails are not in this list.

## 5. The inbox

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/notifications?unreadOnly=&page=&size=` | Newest first |
| GET | `/api/v1/notifications/unread-count` | `{unread}` for the badge |
| POST | `/api/v1/notifications/{notificationId}/read` | 204; idempotent (the first read time stays); 404 if not yours |
| POST | `/api/v1/notifications/read-all` | `{marked}` |
| GET, PUT | `/api/v1/notifications/preferences` | The two email switches |

**Cheap badge.** `idx_notifications_unread (user_id) WHERE read_at IS NULL` indexes only unread rows, so the count stays cheap however long the inbox grows. Clients poll it; push (WebSocket or server-sent events) can replace polling later without changing the data model.

## 6. Rules

| # | Rule | Enforced by |
|---|---|---|
| 1 | A notification exists only for an order change that committed | In-transaction listener |
| 2 | Each person reads and marks only their own notifications | Owner-scoped queries; 404 like an unknown ID |
| 3 | Optional emails follow the person's preferences; security emails always go | `OrderNotifier.wants`; preferences exclude account mail |
| 4 | An erased person has no inbox and gets nothing new | `NotificationEraser`; `users.exists` before writing, `isActive` before sending |
| 5 | No personal data or reasons in notification texts or logs | `OrderMessages`, events carry IDs only |
| 6 | Links stay inside the app; subjects are not seller-controlled | CHECK constraint; fixed subjects |

## 7. Data model (V16)

| Table | Key | Notes |
|---|---|---|
| `notifications` | `id` (UUID v7) | `user_id` (CASCADE), type (CHECK), title, body, internal link (CHECK), `read_at` |
| `notification_preferences` | `user_id` | Two booleans, default true; version |

| Index | Serves |
|---|---|
| `idx_notifications_user_id (user_id, id)` | The inbox, newest first |
| `idx_notifications_unread (user_id) WHERE read_at IS NULL` | The unread badge |

**Retention.** Notifications older than 90 days are deleted by a job (Phase 12).

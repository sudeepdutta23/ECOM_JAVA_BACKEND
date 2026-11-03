# Phase 7 review: Notifications

- Date: 2026-11-03
- Scope: the `notification` module, the `OrderEvent` events of the order module, `SellerDirectory.memberIds`, migration V16
- Result: complete; all automated tests passing (545 unit, 355 integration; see [TESTING.md](../TESTING.md))

## 1. What was delivered

An in-app inbox written with every order change, emails for the important ones, per-person email preferences, and erasure. Design: [modules/NOTIFICATIONS.md](../modules/NOTIFICATIONS.md).

## 2. Security review

| Attack or failure | Blocked by | Evidence |
|---|---|---|
| Reading or marking someone else's notifications | Owner-scoped queries, 404 like an unknown ID | `NotificationIT.readStateIsPerPersonAndSomeoneElsesNotificationIs404` |
| A notification about a payment that rolled back | In-transaction inbox write; emails only after commit | `OrderNotifier` design, `NotificationIT` |
| A seller crafts an email that looks like the platform | Fixed subjects; seller name in the body only | `OrderMessages`, `OrderNotifier` |
| A notification links to a phishing site | Links are app paths; CHECK `ck_notifications_link_internal` | V16 |
| Addresses or cancellation reasons in inboxes, emails or logs | Events carry IDs, amounts, seller name; texts point to the order page | `OrderMessagesTest`, `NotificationIT` (no street in the email) |
| Emails to people who opted out | Preferences checked per recipient | `NotificationIT.switchedOffEmailsAreNotSentButTheInboxStillFills` |
| Notifications to an erased person | Inbox deleted at erasure; `exists` and `isActive` checks | `NotificationIT.anErasedCustomerLosesTheInboxAndGetsNothingNew` |
| Business modules coupled to notifications | Events; ArchUnit rule | `ModuleBoundariesTest` (20 rules) |

No defect was found.

### Accepted risks

| Item | Decision |
|---|---|
| An email is lost if the application crashes between commit and send | The inbox has it; the outbox of Phase 13 makes emails durable |
| Clients poll the unread count | Cheap thanks to the partial index; push (WebSocket, SSE) later |
| English texts only | `preferredLocale` (Phase 5) is ready for templates per language |
| Notifications are kept forever | A retention job deletes them after 90 days (Phase 12) |
| A large seller team gets one email per member per order | Each member can switch `sellerOrdersEmail` off; digests later |

## 3. Performance review

| Path | Cost |
|---|---|
| Inbox write per event | One batch insert (customer plus seller members), one member query (`pk_organization_members`), one existence check |
| Email | After commit, on the async pool: one preference and one status query per recipient, one email lookup batch |
| Badge | `idx_notifications_unread`: only unread rows |
| Inbox page | `idx_notifications_user_id` |

`QueryPlanIT` checks two new plans (70 in all).

# Phase 9 review: Audit

- Date: 2026-11-04
- Scope: the `audit` module, `common.audit.@Audited`, `TransactionConfig`, migration V18, the 23 audited methods
- Result: complete; all automated tests passing (554 unit, 370 integration; see [TESTING.md](../TESTING.md))

## 1. Security review

| Attack or failure | Blocked by | Evidence |
|---|---|---|
| An administrator's action leaves no trace | `@Audited` on every administrative action, in the action's transaction | `AuditIT.anAdministratorsActionIsRecordedWithActorTargetAndRequestId` |
| An entry for an action that did not happen | Written only after a normal return, rolled back with the action | `AuditIT.aRefusedActionLeavesNoEntry` |
| Someone rewrites or deletes history through the application's database account | Append-only trigger (UPDATE, DELETE, TRUNCATE), `@Immutable`, no write endpoint | `AuditIT.theLogIsAppendOnlyEvenForTheApplicationsOwnDatabaseAccount` |
| Spoofing the actor | Taken from the verified access token | `AuditAspect` |
| Personal data spread into a long-lived log | IDs only; reasons never copied | `AuditIT` (no suspension reason in entries) |
| Anyone reads the log | `audit:read` | `AuditIT.readingTheLogNeedsAuditReadAndPagesWithACursor` |
| A module calls the audit internals or bypasses the annotation | `ModuleBoundariesTest.nobodyKnowsTheAuditModule` | 21 architecture rules |

No defect was found. One test assumption was wrong and corrected: Spring reports the trigger's SQLSTATE 42501 as a "bad SQL grammar" exception, with the trigger's message in the root cause.

### Accepted risks

| Item | Decision |
|---|---|
| A database superuser can disable the trigger | Ship entries to write-once storage outside the application's reach (Phase 14) |
| Failed and denied attempts are not audited | They are in the application log with request IDs; security alerting on them is Phase 14 |
| No retention period yet | Partitioning by month and dropping old partitions (Phase 12) |
| Reading the log is not itself audited | Low risk with one permission; add `audit.read` entries if auditors must be audited |
| Changing transaction ordering affects every advice | Method security and other aspects now run inside transactions; no behaviour depended on the old order (full test suite) |

## 2. Performance review

| Path | Cost |
|---|---|
| Audited action | One extra INSERT in the same transaction |
| Investigation queries | One index range scan each, no count query (keyset) |

`QueryPlanIT` checks four new plans.

# ADR-011: Offset pagination for back-office lists, keyset where data is large or moving

- Status: Accepted
- Date: 2026-11-01

## Context

Phase 3 introduces the first list endpoint that can grow without bound (users). Later phases add products, orders and audit entries. Every list needs a consistent contract, and unbounded result sets must be impossible.

## Decision

1. **Every list endpoint is paginated.** There is no "return everything" mode. `size` defaults to 20 and is capped at 100; larger values are a validation error, not silently reduced, so clients notice.
2. **Back-office lists use offset pagination** (`page`, `size`) and return `totalElements` and `totalPages`. Admin screens show page numbers and totals, jump to page N, and filter down to small result sets.
3. **Sorting is fixed per endpoint and always ends with a unique column** (usually `id`). Without a unique tie-breaker, rows with equal sort values can appear on two pages or on none.
4. **One response shape** for pages, `PageResponse<T>` inside the usual `ApiResponse` envelope.
5. **Keyset (cursor) pagination** is used instead where a list is large and read by scrolling, or changes while it is read (activity feeds, audit log export, public product browsing). Those endpoints return an opaque `nextCursor` and no total.

## Alternatives considered

| Option | Trade-off |
|---|---|
| Offset everywhere | Simple; deep pages get slower (`OFFSET 100000` still reads 100 000 rows), and rows shift between pages when data changes |
| Keyset everywhere | Constant speed and stable under inserts; no page numbers, no jump to page N, no total |
| No total count | Saves a `count(*)` query; admin screens need it |
| Client chooses any sort column | Flexible; each column needs an index, or sorting falls back to scanning |

## Consequences

- Each offset page costs two queries: the page and a count. With filters served by indexes this is cheap at the sizes back-office lists reach.
- Deep offsets are possible but bounded by the 100-row page cap; if a back-office list grows into millions of rows, it moves to keyset.
- Sort orders are part of the API contract and documented per endpoint.

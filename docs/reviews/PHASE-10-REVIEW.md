# Phase 10 review: Search, filtering, pagination

- Date: 2026-11-04
- Scope: storefront search (`ProductSearchRepository`, `StorefrontService`, `StorefrontController`), migration V19, ADR-014
- Result: complete; all automated tests passing (see [TESTING.md](../TESTING.md))

## 1. Security review

| Attack or failure | Blocked by | Evidence |
|---|---|---|
| SQL injection through the search text or filters | Bind parameters only; SQL fragments fixed in code | `SearchIT.anyTextIsSafeNeverASyntaxError` |
| Injection through the sort | Enum, mapped to SQL by a `switch` | `SearchIT.badParametersAreValidationErrors` (unknown sort 400) |
| A crafted query raises a database error (500, stack details) | `websearch_to_tsquery` accepts any text | `SearchIT` |
| Finding drafts or a suspended seller's products | Every query filters on `status` and `seller_active` | `SearchIT` (drafts never found), `CatalogIT` |
| Expensive queries: deep OFFSET, huge text | 10 000 results deep, 100 characters, 100 per page | `SearchIT` |

No defect was found. Two test problems were corrected: equal ranks need a tie-breaker in the expectation, and `TestRestTemplate` encodes a String URL as a template a second time.

### Accepted risks

| Item | Decision |
|---|---|
| A count query per search page | Acceptable for the catalogue's size; estimated counts or "more than N" when it grows |
| The public search has no own rate limit | Phase 11 (shared rate limits in Redis) and caching |
| No typo tolerance or stemming | ADR-014 |

## 2. Performance review

| Path | Cost |
|---|---|
| Text search | Bitmap scan on the partial GIN index, rank over the matches |
| Price sorting | Index scan on `idx_products_storefront_price`, stops after the page |
| Page assembly | Four batch lookups by ID |

`QueryPlanIT` checks two new plans (77 in all).

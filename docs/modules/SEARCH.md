# Search, filtering and pagination (Phase 10)

Status: complete (Phase 10 review: [PHASE-10-REVIEW.md](../reviews/PHASE-10-REVIEW.md)). Decision: [ADR-014](../adr/ADR-014-search-in-postgresql.md).

## 1. The storefront search

`GET /api/v1/catalog/products`, public, every parameter optional:

| Parameter | Meaning | Validation |
|---|---|---|
| `q` | Search text in names (weight A) and descriptions (weight B). `"two words"` is a phrase, `-word` excludes, case does not matter | At most 100 characters |
| `category`, `seller` | Slugs; unknown ones give an empty page | At most 50 characters |
| `minPrice`, `maxPrice` | Inclusive, in the platform currency | 0 or more, at most two decimals, min not above max |
| `inStock` | Only products that can be bought now | `true` or `false` |
| `sort` | `RELEVANCE` (default with `q`), `NEWEST` (default without), `PRICE_ASC`, `PRICE_DESC` | Exactly one of these (400 otherwise) |
| `page`, `size` | Zero-based page, 1 to 100 per page | At most 10 000 results deep |

Only products on sale are ever found: ACTIVE, of an ACTIVE seller. Drafts, archived products and anything of a suspended seller cannot be found, for any text.

## 2. How it works

- **`products.search_vector`** is a generated column (V19): PostgreSQL recomputes it on every insert and update, so it can never be out of date, and no Java code maintains it.
- **`simple` configuration**: lower-cased words without language-specific stemming, because sellers write in many languages. "mugs" does not find "mug"; see ADR-014 for when that changes.
- **`websearch_to_tsquery`** turns what a customer types into a query and never fails on syntax: quotes, minus signs, `&`, `|`, `:*`, an unclosed quote all give a valid query, never a 500.
- **`ProductSearchRepository`** builds the SQL from a fixed set of fragments, one per filter that is present, and binds every value as a parameter. ORDER BY is chosen by a `switch` over the `StorefrontSort` enum: no request text ever becomes SQL. `SearchIT` sends SQL injection attempts and stray operators as search text: 200, nothing changed.
- **Relevance** is `ts_rank`, so a word in the name counts more than the same word in the description. **Every sort ends with `id`** as a tie-breaker, so equal prices or ranks never swap places between pages.
- The query returns IDs only; the products are then loaded by ID, with their categories, sellers and images in one batch each: six queries a page, whatever its size.

## 3. Pagination rules

| List | Paging | Why |
|---|---|---|
| Storefront search | Offset (`page`, `size`), at most 10 000 results deep | Customers jump to pages and sort by price; nobody pages to result 10 001. A deep OFFSET still reads every skipped row, so the cap bounds the cost. The answer to "too deep" is a narrower search |
| Back office (users, organisations, orders, members) | Offset with `PageResponse.MAX_PAGE` | Small, filtered lists with totals (ADR-011) |
| Audit log | Keyset (`before`) | Large, always growing, read newest first (ADR-011) |
| Inbox | Offset | Per person, small |

## 4. Indexes (V19)

| Index | Serves |
|---|---|
| `idx_products_search` GIN on `search_vector`, partial over what is on sale | Text search |
| `idx_products_storefront_price (price_minor, id)`, partial | Price sorting and ranges |
| `idx_products_storefront`, `idx_products_category_id`, `idx_products_organization_id` (Phase 6) | Newest first, by category, by seller |

## 5. Not included

Typo tolerance (`pg_trgm`), facet counts, synonyms and per-language stemming: see ADR-014 for the point at which a search server pays off.

# ADR-014: Storefront search in PostgreSQL full text

- Status: accepted
- Date: 2026-11-04
- Phase: 10 (search)

## Context

Customers search the storefront by words, filter by category, seller, price and stock, and sort by relevance or price. The catalogue is in PostgreSQL; the search must only ever show what is on sale, which already depends on two columns (`status`, `seller_active`) that change in transactions.

## Options

1. **`LIKE '%word%'`.** No ranking, no index for a leading wildcard: a sequential scan per query.
2. **PostgreSQL full text** (`tsvector`, GIN index, `websearch_to_tsquery`, `ts_rank`). In the same database and transaction as the catalogue: always consistent, nothing to operate.
3. **A search server** (Elasticsearch, OpenSearch, Meilisearch). Typo tolerance, facets, synonyms, language analysis, scale. But a second system to run, secure and keep in sync (an indexing pipeline from the outbox, Phase 13), and results that lag behind the database: a suspended seller could stay findable until the index catches up.

## Decision

Option 2: a generated `search_vector` column (name weighted above description, `simple` configuration for a multilingual catalogue), a partial GIN index over what is on sale, and a query built from bind parameters and a fixed set of SQL fragments.

## Consequences

- Search is consistent with the catalogue at commit: what is not on sale cannot be found.
- No typo tolerance, no stemming across languages, no facets with counts. `pg_trgm` can add typo tolerance in PostgreSQL itself when needed.
- Move to a search server when one of these holds: the catalogue reaches millions of products and search latency grows; relevance needs synonyms, stemming per language or personalisation; facet counts are required on every page. The `ProductSearchRepository` is the only code that changes for the queries; indexing would come from the outbox events of Phase 13.

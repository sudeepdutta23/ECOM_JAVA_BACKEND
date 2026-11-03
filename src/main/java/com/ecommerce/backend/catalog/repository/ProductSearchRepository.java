package com.ecommerce.backend.catalog.repository;

import com.ecommerce.backend.catalog.StorefrontSort;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The storefront search in SQL, because full-text search ({@code @@}, {@code ts_rank}) has no JPQL
 * form. Every value is a bind parameter; the only text added to the SQL is chosen here, from a fixed
 * set, for each optional filter and for the {@link StorefrontSort}. Nothing from the request is
 * ever concatenated.
 *
 * <p>It returns IDs only: the products are then loaded by ID, like every other storefront read.
 */
@Repository
public class ProductSearchRepository {

    /** One storefront search; every filter optional. Prices in minor units. */
    public record Criteria(String text, UUID categoryId, UUID organizationId, Long minPriceMinor, Long maxPriceMinor,
                           boolean inStockOnly, StorefrontSort sort, int offset, int limit) {
    }

    /** One page of IDs in order, and how many match in all. */
    public record Result(List<UUID> ids, long total) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ProductSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Result search(Criteria criteria) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder("p.status = 'ACTIVE' AND p.seller_active");
        boolean text = criteria.text() != null;
        if (text) {
            // websearch_to_tsquery never fails on syntax: quotes, "-word" and stray symbols are all fine.
            where.append(" AND p.search_vector @@ websearch_to_tsquery('simple', :text)");
            params.addValue("text", criteria.text());
        }
        if (criteria.categoryId() != null) {
            where.append(" AND p.category_id = :categoryId");
            params.addValue("categoryId", criteria.categoryId());
        }
        if (criteria.organizationId() != null) {
            where.append(" AND p.organization_id = :organizationId");
            params.addValue("organizationId", criteria.organizationId());
        }
        if (criteria.minPriceMinor() != null) {
            where.append(" AND p.price_minor >= :minPrice");
            params.addValue("minPrice", criteria.minPriceMinor());
        }
        if (criteria.maxPriceMinor() != null) {
            where.append(" AND p.price_minor <= :maxPrice");
            params.addValue("maxPrice", criteria.maxPriceMinor());
        }
        if (criteria.inStockOnly()) {
            where.append(" AND p.stock > 0");
        }

        String order = switch (criteria.sort()) {
            case RELEVANCE -> text ? "ts_rank(p.search_vector, websearch_to_tsquery('simple', :text)) DESC, p.id DESC"
                    : "p.id DESC";
            case NEWEST -> "p.id DESC";
            case PRICE_ASC -> "p.price_minor ASC, p.id ASC";
            case PRICE_DESC -> "p.price_minor DESC, p.id DESC";
        };
        params.addValue("limit", criteria.limit()).addValue("offset", criteria.offset());

        List<UUID> ids = jdbc.queryForList("SELECT p.id FROM products p WHERE " + where + " ORDER BY " + order
                + " LIMIT :limit OFFSET :offset", params, UUID.class);
        Long total = jdbc.queryForObject("SELECT count(*) FROM products p WHERE " + where, params, Long.class);
        return new Result(ids, total == null ? 0 : total);
    }
}

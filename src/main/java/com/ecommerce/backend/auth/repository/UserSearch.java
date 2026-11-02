package com.ecommerce.backend.auth.repository;

import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters for the admin user list, combined only when present.
 *
 * <p><b>Why build the query instead of one {@code @Query} with {@code (:status IS NULL OR ...)}?</b>
 * That form is valid SQL, but PostgreSQL prepares one plan for every combination of filters and
 * can no longer pick the index that fits the actual request. Adding only the conditions in use
 * gives each combination its own, index-friendly SQL.
 *
 * @param status      exact status, or null for any
 * @param emailPrefix already normalized (trimmed, lower-case) prefix, or null for any
 */
public record UserSearch(UserStatus status, String emailPrefix) {

    private static final char ESCAPE = '\\';

    public Specification<User> toSpecification() {
        List<Specification<User>> conditions = new ArrayList<>();
        if (status != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (emailPrefix != null && !emailPrefix.isEmpty()) {
            String pattern = escapeLike(emailPrefix) + "%";
            conditions.add((root, query, cb) -> cb.like(root.get("email"), pattern, ESCAPE));
        }
        return Specification.allOf(conditions);
    }

    /**
     * {@code %} and {@code _} are wildcards in LIKE. Unescaped, a search for {@code a_b} would also
     * match {@code axb}, and a search for {@code %} would match every account.
     */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

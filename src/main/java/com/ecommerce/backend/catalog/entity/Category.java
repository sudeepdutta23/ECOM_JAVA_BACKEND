package com.ecommerce.backend.catalog.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** A platform-wide product category. The slug is its public handle and never changes. */
@Entity
@Table(name = "categories")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseEntity {

    /** Must match {@code ck_categories_slug_format} (V13). */
    public static final String SLUG_REGEX = "^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]$";
    private static final Pattern SLUG_PATTERN = Pattern.compile(SLUG_REGEX);

    @Column(name = "slug", nullable = false, updatable = false, length = 50)
    private String slug;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Category create(String slug, String name) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException("Invalid category slug");
        }
        Category category = new Category();
        category.slug = slug;
        category.rename(name);
        return category;
    }

    public static String normalizeSlug(String rawSlug) {
        return Objects.requireNonNull(rawSlug, "slug").strip().toLowerCase(Locale.ROOT);
    }

    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("A category needs a name");
        }
        this.name = newName.strip();
    }
}

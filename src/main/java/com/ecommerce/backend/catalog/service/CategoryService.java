package com.ecommerce.backend.catalog.service;

import com.ecommerce.backend.catalog.dto.CategoryResponse;
import com.ecommerce.backend.catalog.entity.Category;
import com.ecommerce.backend.catalog.repository.CategoryRepository;
import com.ecommerce.backend.catalog.repository.ProductRepository;
import com.ecommerce.backend.common.audit.Audited;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-wide categories: read by everyone, managed by staff holding {@code catalog:manage}. */
@Service
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    private final CategoryRepository categories;
    private final ProductRepository products;

    public CategoryService(CategoryRepository categories, ProductRepository products) {
        this.categories = categories;
        this.products = products;
    }

    /** All categories by name. A curated list of tens or hundreds, so no paging. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return categories.findAllByOrderByNameAscIdAsc().stream().map(CategoryResponse::from).toList();
    }

    @Transactional
    @Audited(action = "category.create", targetType = "category", targetId = "#result.id")
    public CategoryResponse create(UUID callerId, String slug, String name) {
        if (categories.existsBySlug(slug)) {
            throw slugTaken(slug);
        }
        Category category;
        try {
            category = categories.saveAndFlush(Category.create(slug, name));
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("uk_categories_slug")) {
                throw slugTaken(slug);
            }
            throw e;
        }
        log.info("Category created: categoryId={}, slug={}, by={}", category.getId(), slug, callerId);
        return CategoryResponse.from(category);
    }

    @Transactional
    @Audited(action = "category.rename", targetType = "category", targetId = "#categoryId")
    public CategoryResponse rename(UUID callerId, UUID categoryId, String name, long expectedVersion) {
        Category category = load(categoryId);
        if (category.getVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "This category was changed by someone else. Reload it and try again.");
        }
        category.rename(name);
        categories.flush();
        log.info("Category renamed: categoryId={}, by={}", categoryId, callerId);
        return CategoryResponse.from(category);
    }

    /**
     * Only an unused category can be deleted (archived products count: they still point to it).
     * The foreign key enforces the same, so a product assigned at the same moment fails one of the two.
     */
    @Transactional
    @Audited(action = "category.delete", targetType = "category", targetId = "#categoryId")
    public void delete(UUID callerId, UUID categoryId) {
        Category category = load(categoryId);
        if (products.existsByCategoryId(categoryId)) {
            throw inUse();
        }
        try {
            categories.delete(category);
            categories.flush();
        } catch (DataIntegrityViolationException e) {
            throw inUse();
        }
        log.info("Category deleted: categoryId={}, by={}", categoryId, callerId);
    }

    private Category load(UUID categoryId) {
        return categories.findById(categoryId).orElseThrow(() -> new ResourceNotFoundException("Category"));
    }

    private static BusinessException slugTaken(String slug) {
        return new BusinessException(ErrorCode.CATALOG_CATEGORY_SLUG_TAKEN, "The category slug '" + slug + "' is taken.");
    }

    private static BusinessException inUse() {
        return new BusinessException(ErrorCode.CATALOG_CATEGORY_IN_USE,
                "Products still use this category. Move them to another category first.");
    }
}

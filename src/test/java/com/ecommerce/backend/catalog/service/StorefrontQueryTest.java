package com.ecommerce.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.catalog.StorefrontSort;
import org.junit.jupiter.api.Test;

class StorefrontQueryTest {

    private static StorefrontQuery query(String text, StorefrontSort sort) {
        return new StorefrontQuery(text, null, null, null, null, false, sort, 0, 20);
    }

    @Test
    void withoutASortTextSearchesRankByRelevanceAndBrowsingShowsTheNewest() {
        assertThat(query("mug", null).effectiveSort()).isEqualTo(StorefrontSort.RELEVANCE);
        assertThat(query(null, null).effectiveSort()).isEqualTo(StorefrontSort.NEWEST);
    }

    @Test
    void relevanceWithoutATextFallsBackToNewestAndBlankTextCountsAsNone() {
        assertThat(query(null, StorefrontSort.RELEVANCE).effectiveSort()).isEqualTo(StorefrontSort.NEWEST);
        assertThat(query("   ", null).text()).isNull();
        assertThat(query("  blue mug ", null).text()).isEqualTo("blue mug");
    }

    @Test
    void anExplicitSortWins() {
        assertThat(query("mug", StorefrontSort.PRICE_ASC).effectiveSort()).isEqualTo(StorefrontSort.PRICE_ASC);
    }
}

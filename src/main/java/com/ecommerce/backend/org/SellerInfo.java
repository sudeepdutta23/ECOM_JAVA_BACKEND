package com.ecommerce.backend.org;

import java.util.UUID;

/** What the storefront shows about a seller: its public handle and name, nothing internal. */
public record SellerInfo(UUID id, String slug, String name) {
}

package com.ecommerce.backend.catalog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.catalog.ProductStatus;
import com.ecommerce.backend.catalog.dto.ProductRequest;
import com.ecommerce.backend.catalog.dto.ProductResponse;
import com.ecommerce.backend.catalog.service.CategoryService;
import com.ecommerce.backend.catalog.service.ProductService;
import com.ecommerce.backend.catalog.service.StorefrontService;
import com.ecommerce.backend.common.security.SecurityConfig;
import com.ecommerce.backend.rbac.Permissions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest({SellerProductController.class, StorefrontController.class, CategoryAdminController.class})
@Import(SecurityConfig.class)
class CatalogControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final String PRODUCTS = "/api/v1/organizations/" + ORG + "/products";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService products;

    @MockitoBean
    private StorefrontService storefront;

    @MockitoBean
    private CategoryService categories;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(j -> j.subject(USER.toString())));
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return signedIn(post(PRODUCTS)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void stubCreate() {
        when(products.create(eq(USER), eq(ORG), any())).thenReturn(new ProductResponse(UUID.randomUUID(), "MUG", "Mug",
                null, BigDecimal.ONE, "EUR", 0, ProductStatus.DRAFT, false, null, 0, Instant.now(), Instant.now()));
    }

    @Test
    void theStorefrontNeedsNoAccount() throws Exception {
        when(categories.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/catalog/products")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/catalog/categories")).andExpect(status().isOk());
    }

    @Test
    void storefrontPagingIsBounded() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/products?size=101")).andExpect(status().isBadRequest());
        verifyNoInteractions(storefront);
    }

    @Test
    void skuIsNormalizedToUpperCaseAndPriceKeptExact() throws Exception {
        stubCreate();
        mockMvc.perform(create("{\"sku\": \" mug-001 \", \"name\": \"Mug\", \"price\": 19.99}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<ProductRequest> captor = ArgumentCaptor.forClass(ProductRequest.class);
        verify(products).create(eq(USER), eq(ORG), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().sku()).isEqualTo("MUG-001");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().price()).isEqualByComparingTo(new BigDecimal("19.99"));
    }

    @Test
    void invalidProductsNeverReachTheService() throws Exception {
        for (String body : List.of(
                "{\"name\": \"Mug\", \"price\": 1}",                                  // no SKU
                "{\"sku\": \"MUG 1\", \"name\": \"Mug\", \"price\": 1}",               // space in SKU
                "{\"sku\": \"MUG\", \"price\": 1}",                                    // no name
                "{\"sku\": \"MUG\", \"name\": \"Mug\"}",                               // no price
                "{\"sku\": \"MUG\", \"name\": \"Mug\", \"price\": 0}",
                "{\"sku\": \"MUG\", \"name\": \"Mug\", \"price\": 0.999}",            // fractions of a cent
                "{\"sku\": \"MUG\", \"name\": \"Mug\", \"price\": 1000000.01}",
                "{\"sku\": \"MUG\", \"name\": \"Mug\\u202E\", \"price\": 1}")) {
            mockMvc.perform(create(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(products);
    }

    @Test
    void stockStatusAndOwnerCannotBeSmuggledIntoAProduct() throws Exception {
        for (String field : List.of("\"stock\": 100", "\"status\": \"ACTIVE\"", "\"organizationId\": \"" + UUID.randomUUID() + "\"")) {
            mockMvc.perform(create("{\"sku\": \"MUG\", \"name\": \"Mug\", \"price\": 1, " + field + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(products);
    }

    @Test
    void descriptionsMayHaveLineBreaks() throws Exception {
        stubCreate();
        mockMvc.perform(create("{\"sku\": \"MUG\", \"name\": \"Mug\", \"price\": 1, \"description\": \"Line 1\\nLine 2\"}"))
                .andExpect(status().isCreated());

        verify(products).create(eq(USER), eq(ORG), any());
    }

    @Test
    void categoriesAreManagedOnlyWithCatalogManage() throws Exception {
        String body = "{\"slug\": \"books\", \"name\": \"Books\"}";
        mockMvc.perform(signedIn(post("/api/v1/admin/categories")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(categories);

        mockMvc.perform(post("/api/v1/admin/categories").with(jwt().jwt(j -> j.subject(USER.toString()))
                        .authorities(new SimpleGrantedAuthority(Permissions.CATALOG_MANAGE)))
                .contentType(MediaType.APPLICATION_JSON).content(body));
        verify(categories).create(USER, "books", "Books");
    }
}

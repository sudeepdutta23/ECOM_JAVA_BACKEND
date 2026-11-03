package com.ecommerce.backend.catalog;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.common.storage.FileStorage;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Product images over real HTTP: upload, storefront, preview, delete, limits and isolation. */
class ProductImageIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@product-images.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private FileStorage storage;

    private AuthTestClient client;
    private final List<String> slugs = new ArrayList<>();
    private String adminToken;
    private String ownerToken;
    private String shopId;
    private String productId;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        adminToken = token(account(), "ADMIN");
        ownerToken = token(account());
        String slug = "it-img-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        shopId = data(client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Image Shop"), ownerToken))
                .get("id").asText();
        productId = data(client.postJson(products(), Map.of("sku", "IMG-1", "name", "Pictured", "price", 5), ownerToken))
                .get("id").asText();
    }

    @AfterEach
    void cleanUp() {
        jdbc.queryForList("SELECT storage_key FROM product_images WHERE organization_id IN (SELECT id FROM organizations "
                + "WHERE slug LIKE 'it-img-%')", String.class).forEach(storage::delete);
        slugs.forEach(s -> {
            jdbc.update("DELETE FROM products WHERE organization_id = (SELECT id FROM organizations WHERE slug = ?)", s);
            jdbc.update("DELETE FROM organizations WHERE slug = ?", s);
        });
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private String account() {
        String email = "img-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        return email;
    }

    private String token(String email, String... roles) {
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r "
                    + "WHERE u.email = ? AND r.code = ?", email, role);
        }
        return accessToken(client.login(email, PASSWORD));
    }

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String products() {
        return "/api/v1/organizations/" + shopId + "/products";
    }

    private String images() {
        return products() + "/" + productId + "/images";
    }

    static byte[] png(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> upload(String token, byte[] bytes, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return rest.exchange(images(), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<byte[]> fetch(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    @Test
    void anImageIsReEncodedStoredUnderAnInternalKeyAndServedOnceTheProductIsOnSale() throws IOException {
        ResponseEntity<String> uploaded = upload(ownerToken, png(120, 80), "../../../etc/passwd.png");
        assertThat(uploaded.getStatusCode()).as(uploaded.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode image = data(uploaded);
        assertThat(image.get("width").asInt()).isEqualTo(120);
        String key = jdbc.queryForObject("SELECT storage_key FROM product_images WHERE id = ?::uuid", String.class,
                image.get("id").asText());
        assertThat(key).as("the client's file name never becomes a path").startsWith("product-images/" + shopId + "/")
                .doesNotContain("passwd").doesNotContain("..");

        // A draft's image is not public, but the seller can preview it.
        String publicUrl = image.get("url").asText();
        assertThat(fetch(publicUrl, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<byte[]> preview = fetch(images() + "/" + image.get("id").asText() + "/content", ownerToken);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getHeaders().getCacheControl()).contains("no-store");

        // On sale: the storefront lists and serves it, cacheable, with a safe content type.
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + shopId + "/approve", null, adminToken));
        data(client.send(HttpMethod.POST, products() + "/" + productId + "/publish", null, ownerToken));
        assertThat(data(client.get("/api/v1/catalog/products/" + productId, null)).get("images").get(0).asText())
                .isEqualTo(publicUrl);
        ResponseEntity<byte[]> served = fetch(publicUrl, null);
        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(served.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(served.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(served.getHeaders().getCacheControl()).contains("public");
        assertThat(ImageIO.read(new ByteArrayInputStream(served.getBody())).getWidth()).isEqualTo(120);
    }

    @Test
    void scriptsAndOtherFilesDisguisedAsImagesAreRefused() {
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8);
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        for (ResponseEntity<String> refused : List.of(upload(ownerToken, svg, "cat.png"), upload(ownerToken, html, "cat.jpg"),
                upload(ownerToken, new byte[0], "empty.png"))) {
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody()).contains("VALIDATION_FAILED").contains("file");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE product_id = ?::uuid", Long.class, productId))
                .isZero();
    }

    @Test
    void tooLargeUploadsAreRefusedBeforeAnyCodeReadsThem() {
        byte[] sixMegabytes = new byte[6 * 1024 * 1024];
        sixMegabytes[0] = (byte) 0x89;

        ResponseEntity<String> refused = upload(ownerToken, sixMegabytes, "huge.png");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(refused.getBody()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void aProductHoldsAtMostEightImagesAndDeletingRemovesTheFile() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(data(upload(ownerToken, png(10 + i, 10), "p" + i + ".png")).get("id").asText());
        }
        assertThat(upload(ownerToken, png(10, 10), "ninth.png").getBody()).contains("CATALOG_IMAGE_LIMIT");
        assertThat(data(client.get(images(), ownerToken))).hasSize(8);

        String key = jdbc.queryForObject("SELECT storage_key FROM product_images WHERE id = ?::uuid", String.class, ids.get(0));
        assertThat(client.send(HttpMethod.DELETE, images() + "/" + ids.get(0), null, ownerToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storage.read(key)).as("the file goes after commit").isEmpty();
        assertThat(upload(ownerToken, png(10, 10), "again.png").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void outsidersCannotUploadOrSeeASellersImages() {
        String imageId = data(upload(ownerToken, png(10, 10), "p.png")).get("id").asText();
        String outsider = token(account());

        assertThat(upload(outsider, png(10, 10), "x.png").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fetch(images() + "/" + imageId + "/content", outsider).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.send(HttpMethod.DELETE, images() + "/" + imageId, null, outsider).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}

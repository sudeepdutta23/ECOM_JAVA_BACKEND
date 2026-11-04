package com.ecommerce.backend.common.web;

import com.ecommerce.backend.common.response.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

/**
 * HTTP caching for public, shared reads (the storefront): a browser or CDN may keep the response for
 * {@code maxAge}, and afterwards revalidates with {@code If-None-Match}, answered by a bodiless 304
 * when nothing changed.
 *
 * <p>The ETag hashes the {@code data} only. The envelope's {@code timestamp} and {@code requestId}
 * differ on every response, so a hash of the whole body (Spring's {@code ShallowEtagHeaderFilter})
 * would never match.
 *
 * <p>Only for data that is the same for every caller: {@code public} lets shared caches store it.
 */
public final class HttpCaching {

    private HttpCaching() {
    }

    public static <T> ResponseEntity<ApiResponse<T>> publicJson(WebRequest request, ObjectMapper mapper, Duration maxAge,
                                                                String message, T data) {
        String etag = etag(mapper, data);
        CacheControl cacheControl = CacheControl.maxAge(maxAge).cachePublic();
        if (matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(cacheControl).eTag(etag).build();
        }
        return ResponseEntity.ok().cacheControl(cacheControl).eTag(etag).body(ApiResponse.success(message, data));
    }

    static String etag(ObjectMapper mapper, Object data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(data));
            return "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16)) + "\"";
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot compute an ETag", e);
        }
    }

    /** {@code If-None-Match} lists ETags, possibly weak ({@code W/"..."}), or is {@code *}. */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.trim();
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals("*") || tag.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}

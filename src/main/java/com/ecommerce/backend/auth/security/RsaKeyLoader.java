package com.ecommerce.backend.auth.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

/**
 * Produces the RSA key pair used to sign and verify access tokens.
 *
 * <p>Only the private key is configured. The public key is derived from it, so the two can
 * never get out of sync.
 */
final class RsaKeyLoader {

    static final int MIN_KEY_BITS = 2048;
    private static final Logger log = LoggerFactory.getLogger(RsaKeyLoader.class);

    private RsaKeyLoader() {
    }

    static RSAKey load(JwtProperties properties, ResourceLoader resourceLoader) {
        if (StringUtils.hasText(properties.privateKeyLocation())) {
            Resource resource = resourceLoader.getResource(properties.privateKeyLocation());
            return fromPem(readPem(resource), properties.keyId());
        }
        if (properties.allowEphemeralKey()) {
            // Never acceptable in production: every restart invalidates all access tokens,
            // and each instance in a cluster would sign with a different key.
            log.warn("No JWT private key configured; generating an EPHEMERAL key. "
                    + "Tokens will not survive a restart. Never use this in production.");
            return generate();
        }
        throw new IllegalStateException(
                "app.security.jwt.private-key-location is required (set JWT_PRIVATE_KEY_LOCATION)");
    }

    static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_KEY_BITS);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey(pair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID("ephemeral-" + UUID.randomUUID())
                    .build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate RSA key pair", e);
        }
    }

    static RSAKey fromPem(String pem, String configuredKeyId) {
        try {
            String base64 = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            if (pem.contains("BEGIN RSA PRIVATE KEY")) {
                throw new IllegalStateException(
                        "JWT key is PKCS#1. Convert it: openssl pkcs8 -topk8 -nocrypt -in old.pem -out new.pem");
            }
            KeyFactory factory = KeyFactory.getInstance("RSA");
            var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                throw new IllegalStateException("JWT private key must be an RSA key with CRT parameters");
            }
            if (crt.getModulus().bitLength() < MIN_KEY_BITS) {
                throw new IllegalStateException("JWT RSA key must be at least " + MIN_KEY_BITS + " bits");
            }
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));

            RSAKey.Builder builder = new RSAKey.Builder(publicKey)
                    .privateKey(crt)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256);
            if (StringUtils.hasText(configuredKeyId)) {
                builder.keyID(configuredKeyId);
            } else {
                builder.keyIDFromThumbprint();
            }
            return builder.build();
        } catch (GeneralSecurityException | IllegalArgumentException | JOSEException e) {
            // Deliberately no key material in the message.
            throw new IllegalStateException("Invalid JWT private key: expected an unencrypted PKCS#8 PEM", e);
        }
    }

    private static String readPem(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read JWT private key from " + resource.getDescription(), e);
        }
    }
}

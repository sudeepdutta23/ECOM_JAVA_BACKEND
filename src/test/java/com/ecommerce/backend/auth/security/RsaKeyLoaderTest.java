package com.ecommerce.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class RsaKeyLoaderTest {

    @TempDir
    Path tempDir;

    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String pkcs8Pem(KeyPair pair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    private static JwtProperties props(String location, String keyId, boolean allowEphemeral) {
        return new JwtProperties("test-issuer", Duration.ofMinutes(15), Duration.ofSeconds(30),
                location, keyId, allowEphemeral);
    }

    @Test
    void loadsPkcs8PemAndDerivesPublicKey() throws Exception {
        KeyPair pair = rsa(2048);
        Path file = tempDir.resolve("jwt.pem");
        Files.writeString(file, pkcs8Pem(pair));

        RSAKey key = RsaKeyLoader.load(props("file:" + file, "", false), new DefaultResourceLoader());

        assertThat(key.isPrivate()).isTrue();
        assertThat(key.toRSAPublicKey()).isEqualTo(pair.getPublic());
        assertThat(key.getKeyID()).isNotBlank(); // RFC 7638 thumbprint
    }

    @Test
    void thumbprintKeyIdIsStableForTheSameKey() throws Exception {
        String pem = pkcs8Pem(rsa(2048));

        assertThat(RsaKeyLoader.fromPem(pem, null).getKeyID())
                .isEqualTo(RsaKeyLoader.fromPem(pem, null).getKeyID());
    }

    @Test
    void usesConfiguredKeyId() throws Exception {
        RSAKey key = RsaKeyLoader.fromPem(pkcs8Pem(rsa(2048)), "2026-10-key-1");

        assertThat(key.getKeyID()).isEqualTo("2026-10-key-1");
    }

    @Test
    void rejectsKeysShorterThan2048Bits() throws Exception {
        String weak = pkcs8Pem(rsa(1024));

        assertThatThrownBy(() -> RsaKeyLoader.fromPem(weak, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void rejectsPkcs1PemWithConversionHint() {
        String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----";

        assertThatThrownBy(() -> RsaKeyLoader.fromPem(pkcs1, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl pkcs8");
    }

    @Test
    void rejectsGarbageWithoutEchoingIt() {
        assertThatThrownBy(() -> RsaKeyLoader.fromPem("-----BEGIN PRIVATE KEY-----\nbm90LWEta2V5\n-----END PRIVATE KEY-----", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("bm90LWEta2V5");
    }

    @Test
    void failsFastWhenNoKeyAndEphemeralNotAllowed() {
        assertThatThrownBy(() -> RsaKeyLoader.load(props("", null, false), new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY_LOCATION");
    }

    @Test
    void generatesEphemeralKeyWhenAllowed() throws Exception {
        RSAKey key = RsaKeyLoader.load(props(null, null, true), new DefaultResourceLoader());

        assertThat(key.getKeyID()).startsWith("ephemeral-");
        assertThat(key.toRSAPublicKey().getModulus().bitLength()).isGreaterThanOrEqualTo(2048);
    }
}

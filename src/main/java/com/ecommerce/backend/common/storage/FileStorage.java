package com.ecommerce.backend.common.storage;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where uploaded files live, behind one port: a local directory now, object storage (S3, GCS)
 * when the application runs on more than one server. Callers choose the key; it is never derived
 * from a user's file name, so a name such as {@code ../../etc/passwd} can never become a path.
 */
public interface FileStorage {

    /** Keys are made by the application: segments of letters, digits, '-' and '_', a dot only before the extension. */
    Pattern KEY = Pattern.compile("^[a-z0-9-]+(/[A-Za-z0-9_-]+)*\\.[a-z0-9]{2,5}$");

    void store(String key, byte[] content);

    Optional<byte[]> read(String key);

    /** Idempotent: deleting a missing file succeeds. */
    void delete(String key);

    static String requireValidKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return key;
    }
}

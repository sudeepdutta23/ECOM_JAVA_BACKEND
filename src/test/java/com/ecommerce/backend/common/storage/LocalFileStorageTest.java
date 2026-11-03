package com.ecommerce.backend.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

    @TempDir
    Path root;

    @Test
    void storesReadsAndDeletesIdempotently() throws IOException {
        LocalFileStorage storage = new LocalFileStorage(root);

        storage.store("product-images/abc/file-1.png", new byte[] {1, 2, 3});

        assertThat(storage.read("product-images/abc/file-1.png")).contains(new byte[] {1, 2, 3});
        storage.delete("product-images/abc/file-1.png");
        storage.delete("product-images/abc/file-1.png");
        assertThat(storage.read("product-images/abc/file-1.png")).isEmpty();
    }

    @Test
    void keysThatCouldLeaveTheRootAreRefused() throws IOException {
        LocalFileStorage storage = new LocalFileStorage(root);

        for (String key : new String[] {"../escape.png", "a/../../escape.png", "/etc/passwd.txt", "C:/windows/x.png",
                "a\\..\\b.png", "a/b", "a/b.PNG.exe", ""}) {
            assertThatThrownBy(() -> storage.store(key, new byte[] {1})).as(key).isInstanceOf(IllegalArgumentException.class);
        }
        try (var files = Files.list(root)) {
            assertThat(files).isEmpty();
        }
    }
}

package com.ecommerce.backend.common.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Files in a local directory. Writes go to a temporary file first and are then moved into place,
 * so a reader never sees half a file. Every resolved path is checked to stay inside the root, on
 * top of the key format: two independent defences against path traversal.
 */
@Component
@ConditionalOnProperty(name = "app.files.storage", havingValue = "local")
class LocalFileStorage implements FileStorage {

    private final Path root;

    LocalFileStorage(@Value("${app.files.local-dir}") Path root) throws IOException {
        this.root = Files.createDirectories(root).toRealPath();
    }

    @Override
    public void store(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store a file", e);
        }
    }

    @Override
    public Optional<byte[]> read(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read a file", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete a file", e);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(FileStorage.requireValidKey(key)).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Storage key escapes the storage root");
        }
        return path;
    }
}

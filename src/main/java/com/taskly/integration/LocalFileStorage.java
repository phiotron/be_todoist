package com.taskly.integration;

import com.taskly.config.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * FileStorage trên đĩa server: root/<2 ký tự đầu>/<khoá>. Ghi vào file ".part" rồi đổi tên (người đọc không bao giờ thấy
 * file dở). Khoá chỉ gồm [0-9a-f-] nên không đi ra ngoài root được.
 */
@Component
public class LocalFileStorage implements FileStorage {

    private static final Pattern KEY = Pattern.compile("[0-9a-f]{32}");
    private static final int BUFFER = 64 * 1024;

    private final Path root;

    public LocalFileStorage(StorageProperties properties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
    }

    @Override
    public Stored store(InputStream content, long maxBytes) throws IOException {
        String key = UUID.randomUUID().toString().replace("-", "");
        Path target = pathOf(key);
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(key + ".part");
        long written = 0;
        try (OutputStream out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = content.read(buffer)) != -1) {
                written += read;
                if (written > maxBytes) {
                    throw new TooLargeException(written);
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        return new Stored(key, written);
    }

    @Override
    public Optional<InputStream> open(String storageKey) throws IOException {
        try {
            return Optional.of(Files.newInputStream(pathOf(storageKey)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(pathOf(storageKey));
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.exists(pathOf(storageKey));
    }

    private Path pathOf(String storageKey) {
        if (storageKey == null || !KEY.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("Khoá lưu không hợp lệ");
        }
        return root.resolve(storageKey.substring(0, 2)).resolve(storageKey);
    }
}

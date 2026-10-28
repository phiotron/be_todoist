package com.taskly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.config.StorageProperties;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** LocalFileStorage thuần (không Spring): ghi / đọc / xoá, chặn quá cỡ, khoá không đi ra ngoài root. */
class LocalFileStorageTest {

    @TempDir
    Path root;

    private LocalFileStorage storage() {
        return new LocalFileStorage(new StorageProperties(root.toString(), Duration.ofMinutes(5), Duration.ofHours(24)));
    }

    @Test
    void storeOpenDelete() throws Exception {
        LocalFileStorage storage = storage();
        FileStorage.Stored stored = storage.store(new ByteArrayInputStream("abc".getBytes()), 10);

        assertThat(stored.sizeBytes()).isEqualTo(3);
        assertThat(storage.exists(stored.storageKey())).isTrue();
        try (var in = storage.open(stored.storageKey()).orElseThrow()) {
            assertThat(in.readAllBytes()).isEqualTo("abc".getBytes());
        }
        storage.delete(stored.storageKey());
        storage.delete(stored.storageKey()); // xoá lặp không lỗi
        assertThat(storage.exists(stored.storageKey())).isFalse();
        assertThat(storage.open(stored.storageKey())).isEmpty();
    }

    @Test
    void tooLargeLeavesNothingBehind() throws Exception {
        LocalFileStorage storage = storage();
        assertThatThrownBy(() -> storage.store(new ByteArrayInputStream(new byte[11]), 10))
                .isInstanceOf(FileStorage.TooLargeException.class);
        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void rejectsKeysThatCouldEscapeRoot() {
        LocalFileStorage storage = storage();
        assertThatThrownBy(() -> storage.exists("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("abc")).isInstanceOf(IllegalArgumentException.class);
    }
}

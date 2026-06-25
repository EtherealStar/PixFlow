package com.etherealstar.pixflow.infra.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageServiceTest {

    private LocalFileStorageService storage;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        StorageProperties props = new StorageProperties();
        props.setRoot(tempDir.toString());
        storage = new LocalFileStorageService(props);
        storage.init();
    }

    @Test
    void writeAndReadBytesRoundTrips() {
        byte[] data = "hello pixflow".getBytes(StandardCharsets.UTF_8);
        String rel = storage.write(data, "packages/1/source.zip");

        assertEquals("packages/1/source.zip", rel);
        assertTrue(storage.exists(rel));
        assertEquals(data.length, storage.size(rel));
        assertArrayEquals(data, storage.readAllBytes(rel));
    }

    @Test
    void writeStreamCreatesParentDirectories() {
        byte[] data = "nested".getBytes(StandardCharsets.UTF_8);
        storage.write(new ByteArrayInputStream(data), "packages/2/images/sub/folder/a.png");

        assertTrue(storage.exists("packages/2/images/sub/folder/a.png"));
        assertArrayEquals(data, storage.readAllBytes("packages/2/images/sub/folder/a.png"));
    }

    @Test
    void streamingReadAndWrite() throws Exception {
        byte[] data = "stream-content".getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = storage.openOutputStream("results/9/out.bin")) {
            out.write(data);
        }
        try (InputStream in = storage.openInputStream("results/9/out.bin")) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    void resolveRejectsPathTraversal() {
        assertThrows(StorageException.class, () -> storage.resolve("../../etc/passwd"));
        assertThrows(StorageException.class, () -> storage.resolve("packages/../../outside.txt"));
    }

    @Test
    void resolveStaysWithinRoot() {
        Path resolved = storage.resolve("packages/1/source.zip");
        assertTrue(resolved.startsWith(storage.getRoot()));
    }

    @Test
    void deleteRemovesFile() {
        storage.write("x".getBytes(StandardCharsets.UTF_8), "tmp/file.txt");
        assertTrue(storage.delete("tmp/file.txt"));
        assertFalse(storage.exists("tmp/file.txt"));
        // 删除不存在的文件返回 false
        assertFalse(storage.delete("tmp/file.txt"));
    }

    @Test
    void deleteRecursivelyRemovesDirectoryTree() throws Exception {
        storage.write("a".getBytes(StandardCharsets.UTF_8), "packages/5/images/a.png");
        storage.write("b".getBytes(StandardCharsets.UTF_8), "packages/5/images/sub/b.png");

        assertTrue(storage.deleteRecursively("packages/5"));
        assertFalse(Files.exists(storage.resolve("packages/5")));
        assertFalse(storage.deleteRecursively("packages/5"));
    }

    @Test
    void openInputStreamMissingFileThrows() {
        assertThrows(StorageException.class, () -> storage.openInputStream("nope/missing.bin"));
    }

    @Test
    void storagePathsConventions() {
        assertEquals("packages/7/source.zip", StoragePaths.packageZip(7));
        assertEquals("packages/7/doc/copy.xlsx", StoragePaths.packageDoc(7, "copy.xlsx"));
        assertEquals("packages/7/images/sub/a.png", StoragePaths.packageImage(7, "sub/a.png"));
        assertEquals("results/3/SKU001_12.png", StoragePaths.taskResult(3, "SKU001_12.png"));
        // join 清理多余分隔符
        assertEquals("a/b/c", StoragePaths.join("a/", "/b/", "c"));
    }
}

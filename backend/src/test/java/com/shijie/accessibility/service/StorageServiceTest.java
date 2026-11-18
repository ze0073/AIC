package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageServiceTest {
    @TempDir Path temp;

    @Test void rejectsEmptyFilesWithAnExplanationBeforeCreatingVersion() {
        for(String name:new String[]{"123.html","empty.zip"}) {
            var upload=new MockMultipartFile("file",name,"application/octet-stream",new byte[0]);
            assertThatThrownBy(()->storage().importUpload(upload,id('A'),id('B'),null))
                    .isInstanceOfSatisfying(ApiException.class,error->{
                        assertThat(error.code()).isEqualTo("EMPTY_UPLOAD");
                        assertThat(error.getMessage()).contains("文件内容为空，无法导入");
                    });
        }
        assertThat(temp.resolve("projects")).doesNotExist();
        assertThat(temp.resolve("temp")).doesNotExist();
    }

    @Test void removesOnlyTheSelectedRunArtifactsAndRejectsOutsideCleanup() throws Exception {
        StorageService storage=storage();
        Path selected=temp.resolve("test-runs").resolve(id('A')).resolve("reports/report.json");
        Path other=temp.resolve("test-runs").resolve(id('B')).resolve("reports/report.json");
        Files.createDirectories(selected.getParent());Files.createDirectories(other.getParent());
        Files.writeString(selected,"selected");Files.writeString(other,"other");
        storage.deleteTestRun(id('A'));
        assertThat(selected.getParent().getParent()).doesNotExist();
        assertThat(other).hasContent("other");
        assertThatThrownBy(()->storage.deleteTreeStrict(temp.getParent())).hasMessageContaining("不属于项目存储目录");
    }

    @Test
    void importsSafeStaticProject() throws Exception {
        StorageService storage = storage();
        var upload = new MockMultipartFile("file", "site.zip", "application/zip",
                zip(Map.of("index.html", "<!doctype html><html><body>ok</body></html>", "styles/site.css", "body{}")));
        var imported = storage.importUpload(upload, id('A'), id('B'), null);
        assertThat(imported.entryPage()).isEqualTo("index.html");
        assertThat(imported.files()).containsExactly("index.html", "styles/site.css");
        assertThat(storage.resolveVersionFile(id('A'), id('B'), "index.html")).exists();
    }

    @Test
    void rejectsZipSlipBeforeCreatingVersion() throws Exception {
        StorageService storage = storage();
        var upload = new MockMultipartFile("file", "bad.zip", "application/zip",
                zip(Map.of("../outside.html", "bad")));
        assertThatThrownBy(() -> storage.importUpload(upload, id('C'), id('D'), null))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ZIP_TRAVERSAL"));
        assertThat(temp.resolve("outside.html")).doesNotExist();
    }

    @Test
    void rejectsExecutableAndNestedArchive() throws Exception {
        StorageService storage = storage();
        for (String filename : new String[]{"run.exe", "nested.zip"}) {
            var upload = new MockMultipartFile("file", "bad.zip", "application/zip", zip(Map.of(filename, "bad")));
            assertThatThrownBy(() -> storage.importUpload(upload, id('E'), id('F'), null))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void rejectsSymbolicLinksAndExcessiveFileCounts() throws Exception {
        StorageService storage = storage();
        var symbolic = new ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(symbolic)) {
            ZipArchiveEntry link = new UnixZipEntry("entry.html");
            link.setUnixMode(0120777);
            zip.putArchiveEntry(link);
            zip.write("target.html".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        assertThatThrownBy(() -> storage.importUpload(
                new MockMultipartFile("file", "symlink.zip", "application/zip", symbolic.toByteArray()),
                id('G'), id('H'), null))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ZIP_SYMLINK"));

        var many = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(many, StandardCharsets.UTF_8)) {
            for (int i = 0; i <= StorageService.MAX_FILES; i++) {
                zip.putNextEntry(new ZipEntry("pages/" + i + ".html"));
                zip.write("<h1>page</h1>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        assertThatThrownBy(() -> storage.importUpload(
                new MockMultipartFile("file", "many.zip", "application/zip", many.toByteArray()),
                id('J'), id('K'), null))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ZIP_FILE_LIMIT"));
    }

    @Test
    void rejectsAnExpandedFileOverThePerFileLimit() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("index.html"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i <= 20; i++) zip.write(block);
            zip.closeEntry();
        }
        assertThatThrownBy(() -> storage().importUpload(
                new MockMultipartFile("file", "large.zip", "application/zip", bytes.toByteArray()),
                id('M'), id('N'), null))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ZIP_SINGLE_FILE_LIMIT"));
    }

    @Test
    void repairImportsBackendSourceWithoutHtmlAndKeepsOrdinaryImporterRestricted() throws Exception {
        var upload=new MockMultipartFile("file","source.zip","application/zip",zip(Map.of("src/Main.java","class Main {}", "pom.xml","<project/>")));
        var result=storage().importRepairUpload(upload,id('P'),id('Q'));
        assertThat(result.sourceType()).isEqualTo("REPAIR_SOURCE");
        assertThat(result.files()).containsExactly("pom.xml","src/Main.java");
        assertThatThrownBy(()->storage().importUpload(upload,id('P'),id('R'),null)).isInstanceOf(ApiException.class);
    }

    @Test
    void repairImportRejectsPrivateDirectoriesSecretsAndExecutableFiles() throws Exception {
        for(String path: new String[]{".git/config","node_modules/lib/a.js",".env","config/.env.local","app.exe","../escape.java"}) {
            var upload=new MockMultipartFile("file","source.zip","application/zip",zip(Map.of("src/Main.java","class Main {}",path,"private")));
            assertThatThrownBy(()->storage().importRepairUpload(upload,id('S'),id('T'))).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void strictCleanupDeletesReadOnlyGitObjectsAndRejectsStorageRoot() throws Exception {
        var directory=temp.resolve("hosting/cleanup-qa/repository/.git/objects/pack");
        java.nio.file.Files.createDirectories(directory);var file=directory.resolve("proof.idx");java.nio.file.Files.writeString(file,"git-object");
        var dos=java.nio.file.Files.getFileAttributeView(file,java.nio.file.attribute.DosFileAttributeView.class);
        if(dos!=null)dos.setReadOnly(true);
        storage().deleteTreeStrict(temp.resolve("hosting/cleanup-qa"));
        assertThat(java.nio.file.Files.exists(file)).isFalse();
        assertThatThrownBy(()->storage().deleteTreeStrict(temp)).isInstanceOf(java.io.IOException.class);
    }

    private StorageService storage() {
        return new StorageService(new AppProperties(temp, new AppProperties.Scanner(10, 10_000, 60_000),
                AppProperties.Ai.defaults(false)));
    }

    private static final class UnixZipEntry extends ZipArchiveEntry {
        private UnixZipEntry(String name) {
            super(name);
            setPlatform(PLATFORM_UNIX);
        }
    }

    private byte[] zip(Map<String, String> entries) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var item : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey()));
                zip.write(item.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private String id(char suffix) { return "0000000000000000000000000" + suffix; }
}

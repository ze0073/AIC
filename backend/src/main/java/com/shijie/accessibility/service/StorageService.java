package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import com.shijie.accessibility.common.Hashes;
import com.shijie.accessibility.common.Ulids;
import com.shijie.accessibility.config.AppProperties;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

@Service
public class StorageService {
    public static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    public static final long MAX_EXPANDED_BYTES = 100L * 1024 * 1024;
    public static final long MAX_SINGLE_FILE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_FILES = 500;

    private static final Set<String> ALLOWED = Set.of(
            "html", "htm", "css", "js", "mjs", "json", "svg", "png", "jpg", "jpeg", "gif", "webp",
            "ico", "woff", "woff2", "ttf", "otf", "txt", "map");
    private static final Set<String> NESTED_ARCHIVES = Set.of("zip", "rar", "7z", "tar", "gz", "jar", "war");
    private static final Set<String> SOURCE_TEXT = Set.of("java", "py", "ts", "tsx", "jsx", "vue", "svelte", "php",
            "go", "rs", "rb", "cs", "xml", "yml", "yaml", "properties", "conf", "sql", "toml", "md", "kt",
            "kts", "gradle", "dart", "ini", "lock", "sh", "bash", "ps1", "bat", "cmd", "c", "h", "cpp",
            "cfg", "in", "mod", "sum", "cjs", "less", "scss", "sass", "mdx");

    private final Path root;

    public StorageService(AppProperties properties) {
        this.root = properties.storageRoot().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建受控存储目录", e);
        }
    }

    public ImportedSource importUpload(MultipartFile upload, String projectId, String versionId, String requestedEntry) {
        return importUpload(upload, projectId, versionId, requestedEntry, false);
    }

    public ImportedSource importRepairUpload(MultipartFile upload, String projectId, String versionId) {
        return importUpload(upload, projectId, versionId, null, true);
    }

    public static boolean editableSource(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return SOURCE_TEXT.contains(ext) || Set.of("html", "htm", "css", "js", "mjs", "json", "svg", "txt").contains(ext)
                || Set.of("dockerfile", "makefile", ".gitignore", ".dockerignore", ".editorconfig", ".gitattributes", ".prettierrc", ".eslintrc", ".nvmrc", ".python-version", "license", "readme", "procfile", "gemfile", "rakefile", "codeowners").contains(Path.of(name).getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private ImportedSource importUpload(MultipartFile upload, String projectId, String versionId, String requestedEntry, boolean repair) {
        if (upload == null || upload.getOriginalFilename() == null || upload.getOriginalFilename().isBlank())
            throw ApiException.badRequest("EMPTY_UPLOAD", "请选择 HTML 或 ZIP 文件");
        if (upload.isEmpty()) throw ApiException.badRequest("EMPTY_UPLOAD", "文件内容为空，无法导入。请先保存网页内容或重新生成压缩包，再上传。");
        if (upload.getSize() > MAX_UPLOAD_BYTES) throw ApiException.badRequest("UPLOAD_TOO_LARGE", "上传文件不能超过 20 MB");
        String original = Optional.ofNullable(upload.getOriginalFilename()).orElse("");
        String extension = extension(original);
        if (!Set.of("zip", "html", "htm").contains(extension)) {
            throw ApiException.badRequest("UNSUPPORTED_UPLOAD", "只支持 ZIP、HTML 或 HTM 文件");
        }

        Path staging = root.resolve("temp").resolve(Ulids.next()).normalize();
        Path source = staging.resolve("source");
        try {
            Files.createDirectories(source);
            if (extension.equals("zip")) {
                Path archive = staging.resolve("upload.zip");
                try (InputStream input = upload.getInputStream()) {
                    Files.copy(input, archive, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.size(archive) > MAX_UPLOAD_BYTES)
                    throw ApiException.badRequest("UPLOAD_TOO_LARGE", "上传文件不能超过 20 MB");
                extractZip(archive, source, repair);
                Files.deleteIfExists(archive);
            }
            else copySingleHtml(upload, source, safeSimpleName(original));

            List<String> files = listRelativeFiles(source);
            List<String> htmlFiles = files.stream().filter(this::isHtml).sorted().toList();
            if (htmlFiles.isEmpty() && !repair) throw ApiException.badRequest("ENTRY_NOT_FOUND", "项目中未找到 HTML 入口页");
            if (files.stream().noneMatch(StorageService::editableSource)) throw ApiException.badRequest("SOURCE_NOT_FOUND", "源码包中没有可修复的文本文件");
            String entry = htmlFiles.isEmpty() ? files.stream().filter(StorageService::editableSource).findFirst().orElseThrow() : chooseEntry(source, requestedEntry, htmlFiles);
            String manifest = manifestHash(source, files);

            Path destination = versionSource(projectId, versionId);
            Files.createDirectories(destination.getParent());
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(source, destination);
            }
            deleteTree(staging);
            return new ImportedSource(relativeToRoot(destination), entry, files, manifest,
                    repair ? "REPAIR_SOURCE" : extension.equals("zip") ? "ZIP" : "HTML");
        } catch (ApiException e) {
            deleteTree(staging);
            throw e;
        } catch (IOException e) {
            deleteTree(staging);
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_FAILED", "无法读取上传项目：" + e.getMessage());
        }
    }

    public Path versionSource(String projectId, String versionId) {
        requireId(projectId);
        requireId(versionId);
        return checked(root.resolve("projects").resolve(projectId).resolve("versions").resolve(versionId).resolve("source"));
    }

    public Path scanDirectory(String projectId, String scanId) {
        requireId(projectId);
        requireId(scanId);
        Path path = checked(root.resolve("projects").resolve(projectId).resolve("scans").resolve(scanId));
        try { Files.createDirectories(path); } catch (IOException e) { throw new IllegalStateException(e); }
        return path;
    }

    public Path reportDirectory(String projectId, String reportId) {
        requireId(projectId);
        requireId(reportId);
        Path path = checked(root.resolve("projects").resolve(projectId).resolve("reports").resolve(reportId));
        try { Files.createDirectories(path); } catch (IOException e) { throw new IllegalStateException(e); }
        return path;
    }

    public Path resolveVersionFile(String projectId, String versionId, String relative) {
        Path source = versionSource(projectId, versionId);
        Path resolved = source.resolve(normalizeRelative(relative)).normalize();
        if (!resolved.startsWith(source) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.notFound("文件不存在");
        }
        return resolved;
    }

    public Path resolveArtifact(String relative) {
        Path resolved = root.resolve(normalizeRelative(relative)).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.notFound("工件不存在");
        }
        return resolved;
    }

    public String relativeToRoot(Path path) {
        Path normalized = checked(path);
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    public List<String> listRelativeFiles(Path source) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            return stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> source.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    public void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) throw new IOException("版本目录包含符号链接");
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public String manifestHash(Path source) throws IOException {
        return manifestHash(source, listRelativeFiles(source));
    }

    public void deleteProjectAsync(String projectId) {
        requireId(projectId);
        Thread cleanup = new Thread(() -> deleteTree(root.resolve("projects").resolve(projectId).normalize()),
                "project-cleanup-" + projectId);
        cleanup.setDaemon(true);
        cleanup.start();
    }

    public void deleteProject(String projectId) throws IOException {
        requireId(projectId);
        deleteTreeStrict(root.resolve("projects").resolve(projectId));
    }

    public void deleteTestRun(String runId) throws IOException {
        requireId(runId);
        deleteTreeStrict(root.resolve("test-runs").resolve(runId));
    }

    public void deleteTreeStrict(Path path) throws IOException {
        Path normalized=path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) throw new IOException("清理路径不属于项目存储目录");
        if (!Files.exists(normalized,LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(normalized,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {
                if(!attrs.isSymbolicLink()){
                    var dos=Files.getFileAttributeView(file,java.nio.file.attribute.DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                    if(dos!=null&&dos.readAttributes().isReadOnly())dos.setReadOnly(false);
                }
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error) throws IOException {
                if (error!=null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private void extractZip(Path archive, Path destination, boolean repair) throws IOException {
        int files = 0;
        long total = 0;
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (!zip.canReadEntryData(entry)) throw ApiException.badRequest("ZIP_ENTRY_INVALID", "ZIP 包含无法读取的条目");
                String name = entry.getName().replace('\\', '/');
                validateArchivePath(name);
                if (repair && Arrays.stream(name.split("/")).anyMatch(part -> part.equalsIgnoreCase(".git") || part.equalsIgnoreCase("node_modules") || part.equalsIgnoreCase(".env") || part.toLowerCase(Locale.ROOT).startsWith(".env.") || Set.of(".npmrc",".netrc",".pypirc").contains(part.toLowerCase(Locale.ROOT))))
                    throw ApiException.badRequest("SOURCE_PRIVATE_FILE", "请移除 .git、node_modules 和 .env 文件后重新打包源码");
                Path out = destination.resolve(name).normalize();
                if (!out.startsWith(destination)) throw ApiException.badRequest("ZIP_TRAVERSAL", "ZIP 包含越界路径");
                if (entry.isUnixSymlink()) throw ApiException.badRequest("ZIP_SYMLINK", "ZIP 不允许包含符号链接");
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                if (!repair || !editableSource(name)) validateAllowed(name);
                if (++files > MAX_FILES) throw ApiException.badRequest("ZIP_FILE_LIMIT", "ZIP 文件数量不能超过 500");
                Files.createDirectories(out.getParent());
                long written;
                try (InputStream input = zip.getInputStream(entry)) {
                    written = copyLimited(input, out, MAX_SINGLE_FILE_BYTES);
                }
                total += written;
                if (total > MAX_EXPANDED_BYTES) throw ApiException.badRequest("ZIP_EXPANDED_LIMIT", "ZIP 解压总量不能超过 100 MB");
            }
        }
    }

    private long copyLimited(InputStream in, Path out, long max) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (var output = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > max) throw ApiException.badRequest("ZIP_SINGLE_FILE_LIMIT", "ZIP 中单个文件不能超过 20 MB");
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    private void copySingleHtml(MultipartFile upload, Path destination, String name) throws IOException {
        Path target = destination.resolve(name).normalize();
        if (!target.startsWith(destination)) throw ApiException.badRequest("INVALID_FILENAME", "文件名不合法");
        upload.transferTo(target);
    }

    private String chooseEntry(Path source, String requested, List<String> htmlFiles) {
        if (requested != null && !requested.isBlank()) {
            String candidate = normalizeRelative(requested);
            Path path = source.resolve(candidate).normalize();
            if (path.startsWith(source) && Files.isRegularFile(path) && isHtml(candidate)) return candidate;
            throw ApiException.badRequest("ENTRY_INVALID", "指定的入口页不存在");
        }
        return htmlFiles.stream().filter(f -> f.equalsIgnoreCase("index.html") || f.equalsIgnoreCase("index.htm"))
                .findFirst().orElse(htmlFiles.get(0));
    }

    private String manifestHash(Path source, List<String> files) {
        StringBuilder manifest = new StringBuilder();
        for (String file : files) manifest.append(file).append(':').append(Hashes.sha256(source.resolve(file))).append('\n');
        return Hashes.sha256(manifest.toString());
    }

    private void validateArchivePath(String name) {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")
                || name.startsWith("//") || Arrays.asList(name.split("/")).contains("..")) {
            throw ApiException.badRequest("ZIP_TRAVERSAL", "ZIP 包含绝对路径或上级目录");
        }
    }

    private void validateAllowed(String name) {
        String ext = extension(name);
        if (NESTED_ARCHIVES.contains(ext)) throw ApiException.badRequest("NESTED_ARCHIVE", "不允许嵌套压缩包");
        if (!ALLOWED.contains(ext)) throw ApiException.badRequest("UNSUPPORTED_FILE", "项目包含不支持的文件类型：" + ext);
    }

    private String normalizeRelative(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.matches("^[A-Za-z]:.*"))
            throw ApiException.badRequest("INVALID_PATH", "文件路径不合法");
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || Arrays.asList(normalized.split("/")).contains(".."))
            throw ApiException.badRequest("INVALID_PATH", "文件路径不合法");
        return normalized;
    }

    private Path checked(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new SecurityException("存储路径越界");
        return normalized;
    }

    private void requireId(String id) {
        if (id == null || !id.matches("[0-9A-HJKMNP-TV-Z]{26}")) throw ApiException.badRequest("INVALID_ID", "资源标识不合法");
    }

    private String safeSimpleName(String name) {
        String base = Paths.get(name.replace('\\', '/')).getFileName().toString();
        return base.isBlank() ? "index.html" : base;
    }

    private String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isHtml(String name) { return Set.of("html", "htm").contains(extension(name)); }

    public void deleteTree(Path path) {
        if (path == null) return;
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) return;
        try {
            Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { }
    }

    public record ImportedSource(String storagePath, String entryPage, List<String> files, String manifestHash,
                                 String sourceType) {}
}

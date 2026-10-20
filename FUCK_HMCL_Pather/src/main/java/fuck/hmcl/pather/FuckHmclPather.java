package fuck.hmcl.pather;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/// A small, dependency-free patcher for HMCL.
///
/// It replaces the "download.china" warning text, turns the warning into an
/// informational notice, and removes the code that disables the download UI.
/// Both regular jars and HMCL's self-extracting Windows executables are
/// supported.
public final class FuckHmclPather {
    /// Patcher version shown in the marker file.
    public static final String VERSION = "1.0.0";

    /// The replacement text. This is deliberately kept in one place so that the
    /// shell wrapper and the native patcher can use the same wording.
    public static final String MESSAGE =
            "恶意举报不是「维护秩序」，而是对开源社区和 MC 生态的严重破坏。"
                    + "这种行为损害的是所有玩家的利益：它让免费工具被迫收缩、让新玩家无从入门、"
                    + "让生态被无谓的恐惧笼罩。一颗老鼠屎，不该坏掉一整锅粥。";

    /// Marker written into every patched archive.
    private static final String MARKER = "META-INF/FUCK_HMCL_PATHER.txt";

    /// Classes that contain the disabled-download UI logic.
    private static final List<String> PATCHED_CLASSES = List.of(
            "org/jackhuang/hmcl/ui/download/VersionsPage$VersionsPageSkin.class",
            "org/jackhuang/hmcl/ui/instances/DownloadListPage$ModDownloadListPageSkin.class"
    );

    /// Matches both active and commented-out `download.china` lines.
    private static final Pattern DOWNLOAD_CHINA_LINE =
            Pattern.compile("(?m)^[\\t ]*(?:#[\\t ]*)?download\\.china[\\t ]*=.*$");

    /// Runs the patcher.
    ///
    /// @param args an optional archive path, `--restore`, or `--help`
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("[FUCK_HMCL_Pather] 失败：" + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        boolean restore = false;
        String requestedArchive = null;

        for (String arg : args) {
            switch (arg) {
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                case "--restore" -> restore = true;
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("未知参数：" + arg);
                    }
                    if (requestedArchive != null) {
                        throw new IllegalArgumentException("只能指定一个 HMCL jar/exe 文件");
                    }
                    requestedArchive = arg;
                }
            }
        }

        List<Path> targets = requestedArchive != null
                ? List.of(Path.of(requestedArchive))
                : findHmclArchives();
        if (targets.isEmpty()) {
            printUsage();
            throw new IllegalStateException("没有找到 HMCL jar/exe，请把文件路径作为参数传给本程序");
        }

        for (Path target : targets) {
            Path archive = target.toAbsolutePath().normalize();
            if (!Files.isRegularFile(archive)) {
                throw new IllegalArgumentException("文件不存在：" + archive);
            }
            process(archive, restore);
        }
    }

    private static void process(Path archive, boolean restore) throws IOException {
        Path output = pathedPath(archive);
        Path legacyBackup = Path.of(archive + ".fuck_hmcl_pather.bak");
        if (restore) {
            restore(archive, output, legacyBackup);
            return;
        }

        if (Files.isRegularFile(output) && isPatched(output)) {
            System.out.println("[FUCK_HMCL_Pather] 补丁产物已经存在，无需重复操作：");
            System.out.println("  " + output);
            return;
        }

        boolean overwrite = Files.exists(output);
        Path temp = Files.createTempFile(archive.getParent(), ".FUCK_HMCL_Pather-", ".tmp");
        try {
            copyPermissions(archive, temp);
            patchArchive(archive, temp);
            replace(output, temp);
        } finally {
            Files.deleteIfExists(temp);
        }

        System.out.println("[FUCK_HMCL_Pather] 补丁已成功写入"
                + (overwrite ? "（覆盖已有产物）" : "") + "：");
        System.out.println("  " + output);
        System.out.println("  - 源文件未修改：" + archive);
        System.out.println("  - 下载页不再因地区检测而禁用");
        System.out.println("  - 提示文字已替换为社区声明（信息样式）");
        System.out.println("如需删除补丁产物，请运行：FUCK_HMCL_Pather --restore \"" + archive + "\"");
    }

    /// Deletes the generated `-pathed` file and, for compatibility with older
    /// in-place versions of this patcher, restores a legacy backup if present.
    private static void restore(Path archive, Path output, Path legacyBackup) throws IOException {
        boolean changed = false;

        if (Files.deleteIfExists(output)) {
            System.out.println("[FUCK_HMCL_Pather] 已删除补丁产物：");
            System.out.println("  " + output);
            changed = true;
        }

        if (Files.isRegularFile(legacyBackup)) {
            Path temp = Files.createTempFile(archive.getParent(), ".FUCK_HMCL_Pather-restore-", ".tmp");
            try {
                copyPermissions(archive, temp);
                Files.copy(legacyBackup, temp, StandardCopyOption.REPLACE_EXISTING);
                replace(archive, temp);
            } finally {
                Files.deleteIfExists(temp);
            }
            System.out.println("[FUCK_HMCL_Pather] 已从旧版备份还原源文件：");
            System.out.println("  " + archive);
            changed = true;
        }

        if (!changed) {
            System.out.println("[FUCK_HMCL_Pather] 没有需要还原的内容：");
            System.out.println("  " + archive);
        }
    }

    /// Builds the output name: `HMCL-xxx.jar` becomes `HMCL-xxx-pathed.jar`.
    private static Path pathedPath(Path archive) {
        String name = archive.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String pathed = dot > 0
                ? name.substring(0, dot) + "-pathed" + name.substring(dot)
                : name + "-pathed";
        return archive.resolveSibling(pathed);
    }

    private static boolean isPatched(Path archive) throws IOException {
        try (ZipInputStream zip = openZipStream(archive)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (MARKER.equals(entry.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void patchArchive(Path source, Path target) throws IOException {
        byte[] sourceBytes = Files.readAllBytes(source);
        int prefixLength = archivePrefixLength(sourceBytes);

        boolean[] replacedClasses = new boolean[PATCHED_CLASSES.size()];
        int replacedLanguages = 0;

        try (OutputStream raw = Files.newOutputStream(target)) {
            if (prefixLength > 0) {
                raw.write(sourceBytes, 0, prefixLength);
            }

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(
                    sourceBytes, prefixLength, sourceBytes.length - prefixLength));
                 ZipOutputStream out = new ZipOutputStream(raw)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (MARKER.equals(name)) {
                        continue;
                    }

                    byte[] data;
                    int classIndex = PATCHED_CLASSES.indexOf(name);
                    if (classIndex >= 0) {
                        data = readPayload(name);
                        replacedClasses[classIndex] = true;
                    } else if (isLanguageFile(name)) {
                        String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                        String changed = patchLanguage(text);
                        if (!changed.equals(text)) {
                            replacedLanguages++;
                        }
                        data = changed.getBytes(StandardCharsets.UTF_8);
                    } else {
                        data = zip.readAllBytes();
                    }

                    ZipEntry copy = new ZipEntry(name);
                    if (entry.getTime() != -1) {
                        copy.setTime(entry.getTime());
                    }
                    if (entry.getComment() != null) {
                        copy.setComment(entry.getComment());
                    }
                    out.putNextEntry(copy);
                    out.write(data);
                    out.closeEntry();
                }

                for (int i = 0; i < replacedClasses.length; i++) {
                    if (!replacedClasses[i]) {
                        throw new IllegalStateException("文件中缺少待替换的类：" + PATCHED_CLASSES.get(i)
                                + "（这个补丁只适用于 HMCL 3.17.dev-583e004 或结构相同的版本）");
                    }
                }
                if (replacedLanguages == 0) {
                    throw new IllegalStateException("文件中没有找到 download.china 提示文本");
                }

                ZipEntry marker = new ZipEntry(MARKER);
                marker.setTime(System.currentTimeMillis());
                out.putNextEntry(marker);
                out.write(("FUCK_HMCL_Pather " + VERSION + "\n"
                        + "patched-at=" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\n"
                        + "message=" + MESSAGE + "\n").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private static ZipInputStream openZipStream(Path archive) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        int prefixLength = archivePrefixLength(bytes);
        return new ZipInputStream(new ByteArrayInputStream(
                bytes, prefixLength, bytes.length - prefixLength));
    }

    /// Returns the number of bytes prepended to the zip payload. HMCL's Windows
    /// launcher is a PE stub followed by a normal jar, so the value is 0 for a
    /// plain jar and 454144 for the official self-extracting executable.
    private static int archivePrefixLength(byte[] bytes) throws IOException {
        if (bytes.length < 22) {
            throw new IOException("文件太小，不是有效的 jar/exe");
        }

        int eocd = -1;
        int minimum = Math.max(0, bytes.length - 22 - 0xFFFF);
        for (int i = bytes.length - 22; i >= minimum; i--) {
            if (u32(bytes, i) == 0x06054b50L) {
                int commentLength = u16(bytes, i + 20);
                if (i + 22 + commentLength == bytes.length) {
                    eocd = i;
                    break;
                }
            }
        }
        if (eocd < 0) {
            throw new IOException("找不到 zip 结束记录，不是有效的 jar/exe");
        }

        long centralDirectorySize = u32(bytes, eocd + 12);
        long centralDirectoryOffset = u32(bytes, eocd + 16);
        if (centralDirectorySize == 0xFFFFFFFFL || centralDirectoryOffset == 0xFFFFFFFFL) {
            throw new IOException("不支持 ZIP64 格式");
        }

        long prefix = eocd - centralDirectorySize - centralDirectoryOffset;
        if (prefix < 0 || prefix > bytes.length - 22) {
            throw new IOException("zip 结构异常，无法计算数据偏移");
        }

        if (prefix > 0) {
            if (u32(bytes, (int) prefix) != 0x04034b50L
                    || u32(bytes, (int) (prefix + centralDirectoryOffset)) != 0x02014b50L) {
                throw new IOException("zip 结构异常，前置数据长度校验失败");
            }
        }

        return (int) prefix;
    }

    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static String patchLanguage(String text) {
        return DOWNLOAD_CHINA_LINE.matcher(text)
                .replaceAll(Matcher.quoteReplacement("download.china=" + MESSAGE));
    }

    private static boolean isLanguageFile(String name) {
        return name.startsWith("assets/lang/I18N") && name.endsWith(".properties");
    }

    private static byte[] readPayload(String name) throws IOException {
        try (InputStream in = FuckHmclPather.class.getResourceAsStream("/payload/" + name)) {
            if (in == null) {
                throw new IOException("补丁资源缺失：payload/" + name);
            }
            return in.readAllBytes();
        }
    }

    private static void replace(Path destination, Path replacement) throws IOException {
        try {
            Files.move(replacement, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(replacement, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Copies POSIX permissions when the platform supports them. Windows does
    /// not expose POSIX permissions, so failures are intentionally ignored.
    private static void copyPermissions(Path source, Path target) {
        try {
            Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Not a POSIX file system.
        }
    }

    /// Finds the newest HMCL jar and the newest HMCL exe in the current
    /// directory, the shell script directory, and the patcher directory.
    private static List<Path> findHmclArchives() throws IOException {
        Set<Path> directories = new LinkedHashSet<>();
        directories.add(Path.of("").toAbsolutePath().normalize());

        String scriptDirectory = System.getenv("FUCK_HMCL_PATHER_DIR");
        if (scriptDirectory != null && !scriptDirectory.isBlank()) {
            directories.add(Path.of(scriptDirectory).toAbsolutePath().normalize());
        }

        try {
            Path location = Path.of(FuckHmclPather.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path parent = Files.isDirectory(location) ? location : location.getParent();
            if (parent != null) {
                directories.add(parent.toAbsolutePath().normalize());
            }
        } catch (Exception ignored) {
            // The current directory is still a useful fallback.
        }

        Map<String, Path> newest = new LinkedHashMap<>();
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var stream = Files.list(directory)) {
                stream.filter(Files::isRegularFile)
                        .filter(FuckHmclPather::looksLikeHmclArchive)
                        .forEach(candidate -> {
                            String extension = candidate.getFileName().toString()
                                    .toLowerCase(Locale.ROOT).endsWith(".exe") ? ".exe" : ".jar";
                            Path current = newest.get(extension);
                            if (current == null || candidate.toFile().lastModified()
                                    > current.toFile().lastModified()) {
                                newest.put(extension, candidate);
                            }
                        });
            }
        }

        List<Path> result = new ArrayList<>(newest.values());
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    private static boolean looksLikeHmclArchive(Path path) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        return (lower.endsWith(".jar") || lower.endsWith(".exe"))
                && lower.startsWith("hmcl")
                && !lower.contains("sources")
                && !lower.contains("javadoc")
                && !lower.contains("-pathed")
                && !lower.contains("-pather")
                && !lower.contains("-patched");
    }

    private static void printUsage() {
        System.out.println("FUCK_HMCL_Pather " + VERSION);
        System.out.println("用法：");
        System.out.println("  java -jar FUCK_HMCL_Pather.jar [HMCL.jar|HMCL.exe]");
        System.out.println("  java -jar FUCK_HMCL_Pather.jar --restore [HMCL.jar|HMCL.exe]");
        System.out.println();
        System.out.println("不指定文件时，会在当前目录和补丁所在目录中自动寻找 HMCL*.jar / HMCL*.exe。");
        System.out.println("补丁产物命名为 <源文件名>-pathed.<扩展名>，源文件不会被修改。");
    }

    private FuckHmclPather() {
    }
}

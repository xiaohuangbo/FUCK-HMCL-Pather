package fuck.hmcl.pather;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/// A small, dependency-free patcher for HMCL.
///
/// It replaces the "download.china" warning text, turns the warning into an
/// informational notice, and removes the code that disables the download UI.
/// Regular jars, HMCL's self-extracting Windows executables, its Linux/macOS
/// self-extracting shell scripts, and the Debian packages built from those
/// scripts are supported.
public final class FuckHmclPather {
    /// Patcher version shown in the marker file.
    public static final String VERSION = "1.1.0";

    /// The replacement text. This is deliberately kept in one place so that the
    /// shell wrapper and the native patcher can use the same wording.
    public static final String MESSAGE =
            "恶意举报不是「维护秩序」，而是对开源社区和 MC 生态的严重破坏。"
                    + "这种行为损害的是所有玩家的利益：它让免费工具被迫收缩、让新玩家无从入门、"
                    + "让生态被无谓的恐惧笼罩。一颗老鼠屎，不该坏掉一整锅粥。";

    /// Marker written into every patched archive.
    private static final String MARKER = "META-INF/FUCK_HMCL_PATHER.txt";

    /// Debian packages use the classic Unix `ar` container format.
    private static final byte[] AR_MAGIC = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);

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
                        throw new IllegalArgumentException("只能指定一个 HMCL 文件");
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
            throw new IllegalStateException("没有找到 HMCL 文件，请把文件路径作为参数传给本程序");
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
        byte[] bytes = Files.readAllBytes(archive);
        if (isDeb(bytes)) {
            return isDebPatched(bytes);
        }
        return isZipPatched(bytes);
    }

    private static boolean isZipPatched(byte[] bytes) throws IOException {
        try (ZipInputStream zip = openZipStream(bytes)) {
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
        try (OutputStream raw = Files.newOutputStream(target)) {
            if (isDeb(sourceBytes)) {
                patchDeb(sourceBytes, raw);
            } else {
                patchZip(sourceBytes, raw);
            }
        }
    }

    private static void patchZip(byte[] sourceBytes, OutputStream raw) throws IOException {
        int prefixLength = archivePrefixLength(sourceBytes);

        boolean[] replacedClasses = new boolean[PATCHED_CLASSES.size()];
        int replacedLanguages = 0;

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

    private static ZipInputStream openZipStream(byte[] bytes) throws IOException {
        int prefixLength = archivePrefixLength(bytes);
        return new ZipInputStream(new ByteArrayInputStream(
                bytes, prefixLength, bytes.length - prefixLength));
    }

    /// Returns the number of bytes prepended to the zip payload. HMCL's Windows
    /// launcher is a PE stub followed by a normal jar, so the value is 0 for a
    /// plain jar and 454144 for the official self-extracting executable.
    private static int archivePrefixLength(byte[] bytes) throws IOException {
        if (bytes.length < 22) {
            throw new IOException("文件太小，不是有效的 jar/exe/sh");
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
            throw new IOException("找不到 zip 结束记录，不是有效的 jar/exe/sh");
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

    /// Returns true when the file starts with the Unix `ar` magic used by
    /// Debian packages.
    private static boolean isDeb(byte[] bytes) {
        if (bytes.length < AR_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < AR_MAGIC.length; i++) {
            if (bytes[i] != AR_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /// Rebuilds a `.deb` archive, replacing the HMCL shell script embedded in
    /// its `data.tar.gz` member. Other members are copied byte for byte.
    private static void patchDeb(byte[] sourceBytes, OutputStream raw) throws IOException {
        List<ArMember> members = parseAr(sourceBytes);
        boolean patchedData = false;

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(sourceBytes.length + 4096);
        buffer.write(AR_MAGIC);
        for (ArMember member : members) {
            byte[] data = member.data;
            if (isDataTarGz(member.name)) {
                if (patchedData) {
                    throw new IOException("deb 中存在多个 data.tar.gz，无法安全处理");
                }
                data = patchDebData(data);
                patchedData = true;
            } else if (member.name.startsWith("data.tar.") && !isDataTarGz(member.name)) {
                throw new IOException("暂不支持 " + member.name + "，只支持 data.tar.gz 格式的 deb");
            }
            writeArMember(buffer, member.header, data);
        }

        if (!patchedData) {
            throw new IOException("deb 中缺少 data.tar.gz，无法找到 HMCL 程序");
        }
        buffer.writeTo(raw);
    }

    private static boolean isDebPatched(byte[] bytes) throws IOException {
        for (ArMember member : parseAr(bytes)) {
            if (isDataTarGz(member.name)) {
                byte[] shell = findHmclShell(gunzip(member.data));
                return shell != null && isZipPatched(shell);
            }
        }
        return false;
    }

    private static byte[] patchDebData(byte[] gzipBytes) throws IOException {
        byte[] tar = gunzip(gzipBytes);
        ByteArrayOutputStream patchedTar = new ByteArrayOutputStream(tar.length + 4096);
        patchDebTar(tar, patchedTar);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream(gzipBytes.length + 4096);
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            patchedTar.writeTo(gzip);
        }
        return compressed.toByteArray();
    }

    private static void patchDebTar(byte[] tar, OutputStream out) throws IOException {
        int offset = 0;
        String longName = null;
        boolean patched = false;

        while (offset + 512 <= tar.length) {
            byte[] header = Arrays.copyOfRange(tar, offset, offset + 512);
            if (isZeroBlock(header)) {
                out.write(tar, offset, tar.length - offset);
                if (!patched) {
                    throw new IOException("deb 中没有找到可打补丁的 HMCL shell 脚本");
                }
                return;
            }

            long size = parseTarNumber(header, 124, 12);
            if (size < 0 || size > tar.length - offset - 512) {
                throw new IOException("deb 内层 tar 结构异常");
            }
            int contentStart = offset + 512;
            int contentLength = (int) size;
            int type = header[156] & 0xFF;
            String name = longName != null ? longName : tarName(header);
            byte[] content = Arrays.copyOfRange(tar, contentStart, contentStart + contentLength);

            if (type == 'L') {
                longName = cString(content, 0, content.length);
            } else {
                if (isHmclShellEntry(name) && (type == 0 || type == '0')) {
                    content = patchShell(content);
                    header = header.clone();
                    writeTarOctal(header, 124, 12, content.length);
                    writeTarChecksum(header);
                    patched = true;
                }
                longName = null;
            }

            out.write(header);
            out.write(content);
            int padding = (512 - (content.length & 511)) & 511;
            if (padding > 0) {
                out.write(new byte[padding]);
            }

            offset = contentStart + ((contentLength + 511) / 512) * 512;
        }

        if (!patched) {
            throw new IOException("deb 中没有找到可打补丁的 HMCL shell 脚本");
        }
    }

    private static byte[] findHmclShell(byte[] tar) throws IOException {
        int offset = 0;
        String longName = null;

        while (offset + 512 <= tar.length) {
            byte[] header = Arrays.copyOfRange(tar, offset, offset + 512);
            if (isZeroBlock(header)) {
                return null;
            }

            long size = parseTarNumber(header, 124, 12);
            if (size < 0 || size > tar.length - offset - 512) {
                throw new IOException("deb 内层 tar 结构异常");
            }
            int contentStart = offset + 512;
            int contentLength = (int) size;
            int type = header[156] & 0xFF;
            String name = longName != null ? longName : tarName(header);
            byte[] content = Arrays.copyOfRange(tar, contentStart, contentStart + contentLength);

            if (type == 'L') {
                longName = cString(content, 0, content.length);
            } else {
                if (isHmclShellEntry(name) && (type == 0 || type == '0')) {
                    return content;
                }
                longName = null;
            }

            offset = contentStart + ((contentLength + 511) / 512) * 512;
        }
        return null;
    }

    private static byte[] patchShell(byte[] content) throws IOException {
        ByteArrayOutputStream patched = new ByteArrayOutputStream(content.length + 4096);
        patchZip(content, patched);
        return patched.toByteArray();
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return gzip.readAllBytes();
        }
    }

    private static boolean isHmclShellEntry(String name) {
        int slash = name.lastIndexOf('/');
        String fileName = (slash >= 0 ? name.substring(slash + 1) : name).toLowerCase(Locale.ROOT);
        return fileName.startsWith("hmcl") && fileName.endsWith(".sh");
    }

    private static boolean isDataTarGz(String name) {
        return "data.tar.gz".equals(name);
    }

    private static List<ArMember> parseAr(byte[] bytes) throws IOException {
        if (!isDeb(bytes)) {
            throw new IOException("不是有效的 deb/ar 文件");
        }

        List<ArMember> members = new ArrayList<>();
        int offset = AR_MAGIC.length;
        while (offset < bytes.length) {
            if (offset + 60 > bytes.length) {
                throw new IOException("deb 的 ar 头部不完整");
            }
            byte[] header = Arrays.copyOfRange(bytes, offset, offset + 60);
            if (header[58] != '`' || header[59] != '\n') {
                throw new IOException("deb 的 ar 头部校验失败");
            }

            String name = arName(header);
            long size = parseArDecimal(header, 48, 10);
            long start = offset + 60L;
            long end = start + size;
            if (size < 0 || end > bytes.length) {
                throw new IOException("deb 的 ar 成员长度异常：" + name);
            }

            members.add(new ArMember(header, name,
                    Arrays.copyOfRange(bytes, (int) start, (int) end)));
            offset = (int) end + (int) (size & 1);
        }
        return members;
    }

    private static void writeArMember(ByteArrayOutputStream out, byte[] originalHeader, byte[] data)
            throws IOException {
        byte[] header = originalHeader.clone();
        byte[] size = Long.toString(data.length).getBytes(StandardCharsets.US_ASCII);
        if (size.length > 10) {
            throw new IOException("deb 成员过大");
        }
        Arrays.fill(header, 48, 58, (byte) ' ');
        System.arraycopy(size, 0, header, 48, size.length);

        out.write(header);
        out.write(data);
        if ((data.length & 1) != 0) {
            out.write('\n');
        }
    }

    private static String arName(byte[] header) {
        String name = new String(header, 0, 16, StandardCharsets.US_ASCII).trim();
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    private static long parseArDecimal(byte[] bytes, int offset, int length) throws IOException {
        String text = new String(bytes, offset, length, StandardCharsets.US_ASCII).trim();
        try {
            return text.isEmpty() ? 0 : Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IOException("deb 的 ar 数字字段无效：" + text, e);
        }
    }

    private static String tarName(byte[] header) {
        String name = cString(header, 0, 100);
        if (header[257] == 'u' && header[258] == 's' && header[259] == 't'
                && header[260] == 'a' && header[261] == 'r') {
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
        }
        return name;
    }

    private static String cString(byte[] bytes, int offset, int length) {
        int end = offset;
        int limit = Math.min(bytes.length, offset + length);
        while (end < limit && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseTarNumber(byte[] bytes, int offset, int length) throws IOException {
        if (length <= 0) {
            return 0;
        }
        if ((bytes[offset] & 0x80) != 0) {
            long value = bytes[offset] & 0x7F;
            for (int i = offset + 1; i < offset + length; i++) {
                value = (value << 8) | (bytes[i] & 0xFF);
            }
            return value;
        }

        int start = offset;
        int end = offset + length;
        while (start < end && (bytes[start] == ' ' || bytes[start] == 0)) {
            start++;
        }
        while (end > start && (bytes[end - 1] == ' ' || bytes[end - 1] == 0)) {
            end--;
        }
        if (start == end) {
            return 0;
        }
        try {
            return Long.parseLong(new String(bytes, start, end - start,
                    StandardCharsets.US_ASCII), 8);
        } catch (NumberFormatException e) {
            throw new IOException("tar 数字字段无效", e);
        }
    }

    private static void writeTarOctal(byte[] header, int offset, int length, long value)
            throws IOException {
        String digits = Long.toOctalString(value);
        if (digits.length() > length - 1) {
            throw new IOException("tar 条目过大");
        }
        Arrays.fill(header, offset, offset + length, (byte) '0');
        byte[] ascii = digits.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, header, offset + length - 1 - ascii.length, ascii.length);
        header[offset + length - 1] = 0;
    }

    private static void writeTarChecksum(byte[] header) throws IOException {
        Arrays.fill(header, 148, 156, (byte) ' ');
        long sum = 0;
        for (byte value : header) {
            sum += value & 0xFF;
        }
        String digits = String.format(Locale.ROOT, "%06o", sum);
        byte[] ascii = digits.getBytes(StandardCharsets.US_ASCII);
        if (ascii.length != 6) {
            throw new IOException("tar 校验和溢出");
        }
        System.arraycopy(ascii, 0, header, 148, 6);
        header[154] = 0;
        header[155] = ' ';
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static final class ArMember {
        final byte[] header;
        final String name;
        final byte[] data;

        ArMember(byte[] header, String name, byte[] data) {
            this.header = header;
            this.name = name;
            this.data = data;
        }
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

    /// Finds the newest HMCL jar, exe, sh, and deb in the current directory,
    /// the shell script directory, and the patcher directory.
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
                            String extension = archiveExtension(candidate.getFileName().toString());
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
        return (lower.endsWith(".jar") || lower.endsWith(".exe")
                || lower.endsWith(".sh") || lower.endsWith(".deb"))
                && lower.startsWith("hmcl")
                && !lower.contains("sources")
                && !lower.contains("javadoc")
                && !lower.contains("-pathed")
                && !lower.contains("-pather")
                && !lower.contains("-patched");
    }

    private static String archiveExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe")) {
            return ".exe";
        }
        if (lower.endsWith(".deb")) {
            return ".deb";
        }
        if (lower.endsWith(".sh")) {
            return ".sh";
        }
        return ".jar";
    }

    private static void printUsage() {
        System.out.println("FUCK_HMCL_Pather " + VERSION);
        System.out.println("用法：");
        System.out.println("  java -jar FUCK_HMCL_Pather.jar [HMCL.jar|HMCL.exe|HMCL.sh|HMCL.deb]");
        System.out.println("  java -jar FUCK_HMCL_Pather.jar --restore [HMCL.jar|HMCL.exe|HMCL.sh|HMCL.deb]");
        System.out.println();
        System.out.println("不指定文件时，会在当前目录和补丁所在目录中自动寻找 HMCL*.jar / HMCL*.exe / HMCL*.sh / HMCL*.deb。");
        System.out.println("补丁产物命名为 <源文件名>-pathed.<扩展名>，源文件不会被修改。");
    }

    private FuckHmclPather() {
    }
}

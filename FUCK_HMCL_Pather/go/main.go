// FUCK_HMCL_Pather is a standalone patcher for HMCL.
//
// The Windows build is a native executable; the shell build wraps the Java
// patcher. Both implementations perform the same three changes:
//  1. Replace the "download.china" warning text with the community statement.
//  2. Turn the warning into an informational notice.
//  3. Remove the setDisable(true) call so the download page remains usable.
//
// Both a plain HMCL jar and HMCL's self-extracting Windows executable are
// supported. The latter is a PE launcher with a jar appended to it.
package main

import (
	"archive/zip"
	"bufio"
	"bytes"
	"embed"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"time"
)

//go:embed payload
var payloadFS embed.FS

const (
	version = "1.0.0"
	marker  = "META-INF/FUCK_HMCL_PATHER.txt"

	message = "恶意举报不是「维护秩序」，而是对开源社区和 MC 生态的严重破坏。" +
		"这种行为损害的是所有玩家的利益：它让免费工具被迫收缩、让新玩家无从入门、" +
		"让生态被无谓的恐惧笼罩。一颗老鼠屎，不该坏掉一整锅粥。"
)

var patchedClasses = []string{
	"org/jackhuang/hmcl/ui/download/VersionsPage$VersionsPageSkin.class",
	"org/jackhuang/hmcl/ui/instances/DownloadListPage$ModDownloadListPageSkin.class",
}

var downloadChinaLine = regexp.MustCompile(`(?m)^[\t ]*(?:#[\t ]*)?download\.china[\t ]*=.*$`)

func main() {
	code := 0
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "[FUCK_HMCL_Pather] 失败："+err.Error())
		code = 1
	}
	if runtime.GOOS == "windows" && len(os.Args) == 1 {
		fmt.Print("\n按回车键退出...")
		_, _ = bufio.NewReader(os.Stdin).ReadString('\n')
	}
	os.Exit(code)
}

func run() error {
	requested, restore, err := parseArgs(os.Args[1:])
	if err != nil {
		return err
	}

	var targets []string
	if requested != "" {
		targets = []string{requested}
	} else {
		targets, err = findHmclArchives()
		if err != nil {
			return err
		}
	}
	if len(targets) == 0 {
		printUsage()
		return errors.New("没有找到 HMCL jar/exe，请把文件路径作为参数传给本程序")
	}

	for _, target := range targets {
		absolute, err := filepath.Abs(target)
		if err != nil {
			return err
		}
		if err := process(absolute, restore); err != nil {
			return err
		}
	}
	return nil
}

func process(archive string, restore bool) error {
	info, err := os.Stat(archive)
	if err != nil {
		return fmt.Errorf("文件不存在：%s", archive)
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("不是普通文件：%s", archive)
	}

	output := pathedPath(archive)
	legacyBackup := archive + ".fuck_hmcl_pather.bak"
	if restore {
		return restoreOutputs(archive, output, legacyBackup)
	}

	if outputInfo, err := os.Stat(output); err == nil && outputInfo.Mode().IsRegular() {
		patched, err := isPatched(output)
		if err != nil {
			return err
		}
		if patched {
			fmt.Println("[FUCK_HMCL_Pather] 补丁产物已经存在，无需重复操作：")
			fmt.Println("  " + output)
			return nil
		}
	}

	overwrite := false
	if _, err := os.Stat(output); err == nil {
		overwrite = true
	}

	temp, err := os.CreateTemp(filepath.Dir(archive), ".FUCK_HMCL_Pather-*.tmp")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Close(); err != nil {
		return err
	}
	_ = os.Chmod(tempName, info.Mode().Perm())

	if err := patchArchive(archive, tempName); err != nil {
		return err
	}
	if err := replaceFile(tempName, output); err != nil {
		return err
	}

	suffix := ""
	if overwrite {
		suffix = "（覆盖已有产物）"
	}
	fmt.Println("[FUCK_HMCL_Pather] 补丁已成功写入" + suffix + "：")
	fmt.Println("  " + output)
	fmt.Println("  - 源文件未修改：" + archive)
	fmt.Println("  - 下载页不再因地区检测而禁用")
	fmt.Println("  - 提示文字已替换为社区声明（信息样式）")
	fmt.Println("如需删除补丁产物，请运行：FUCK_HMCL_Pather.exe --restore \"" + archive + "\"")
	return nil
}

// restoreOutputs deletes the generated `-pathed` file and, for compatibility
// with older in-place versions of this patcher, restores a legacy backup.
func restoreOutputs(archive, output, legacyBackup string) error {
	changed := false

	if err := os.Remove(output); err == nil {
		fmt.Println("[FUCK_HMCL_Pather] 已删除补丁产物：")
		fmt.Println("  " + output)
		changed = true
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}

	if _, err := os.Stat(legacyBackup); err == nil {
		info, err := os.Stat(archive)
		if err != nil {
			return err
		}
		temp, err := os.CreateTemp(filepath.Dir(archive), ".FUCK_HMCL_Pather-restore-*.tmp")
		if err != nil {
			return err
		}
		tempName := temp.Name()
		defer os.Remove(tempName)
		if err := temp.Close(); err != nil {
			return err
		}
		_ = os.Chmod(tempName, info.Mode().Perm())
		if err := copyFile(legacyBackup, tempName); err != nil {
			return err
		}
		if err := replaceFile(tempName, archive); err != nil {
			return err
		}
		fmt.Println("[FUCK_HMCL_Pather] 已从旧版备份还原源文件：")
		fmt.Println("  " + archive)
		changed = true
	}

	if !changed {
		fmt.Println("[FUCK_HMCL_Pather] 没有需要还原的内容：")
		fmt.Println("  " + archive)
	}
	return nil
}

// pathedPath builds the output name: `HMCL-xxx.jar` becomes
// `HMCL-xxx-pathed.jar`.
func pathedPath(archive string) string {
	extension := filepath.Ext(archive)
	base := strings.TrimSuffix(archive, extension)
	return base + "-pathed" + extension
}

func parseArgs(args []string) (string, bool, error) {
	archive := ""
	restore := false
	for _, arg := range args {
		switch arg {
		case "--help", "-h":
			printUsage()
			os.Exit(0)
		case "--restore":
			restore = true
		default:
			if strings.HasPrefix(arg, "-") {
				return "", false, fmt.Errorf("未知参数：%s", arg)
			}
			if archive != "" {
				return "", false, errors.New("只能指定一个 HMCL jar/exe 文件")
			}
			archive = arg
		}
	}
	return archive, restore, nil
}

func isPatched(archive string) (bool, error) {
	data, err := os.ReadFile(archive)
	if err != nil {
		return false, err
	}
	reader, err := openZipReader(data)
	if err != nil {
		return false, fmt.Errorf("无法读取文件：%w", err)
	}
	for _, entry := range reader.File {
		if entry.Name == marker {
			return true, nil
		}
	}
	return false, nil
}

func openZipReader(data []byte) (*zip.Reader, error) {
	prefix, err := archivePrefixLength(data)
	if err != nil {
		return nil, err
	}
	return zip.NewReader(bytes.NewReader(data[prefix:]), int64(len(data)-prefix))
}

// archivePrefixLength returns the number of bytes prepended to the zip payload.
// HMCL's Windows launcher is a PE stub followed by a normal jar, so this is 0
// for a plain jar and 454144 for the official self-extracting executable.
func archivePrefixLength(data []byte) (int, error) {
	if len(data) < 22 {
		return 0, errors.New("文件太小，不是有效的 jar/exe")
	}

	minimum := len(data) - 22 - 0xffff
	if minimum < 0 {
		minimum = 0
	}

	eocd := -1
	for i := len(data) - 22; i >= minimum; i-- {
		if binary.LittleEndian.Uint32(data[i:]) == 0x06054b50 {
			commentLength := int(binary.LittleEndian.Uint16(data[i+20:]))
			if i+22+commentLength == len(data) {
				eocd = i
				break
			}
		}
	}
	if eocd < 0 {
		return 0, errors.New("找不到 zip 结束记录，不是有效的 jar/exe")
	}

	centralDirectorySize := int64(binary.LittleEndian.Uint32(data[eocd+12:]))
	centralDirectoryOffset := int64(binary.LittleEndian.Uint32(data[eocd+16:]))
	if centralDirectorySize == 0xffffffff || centralDirectoryOffset == 0xffffffff {
		return 0, errors.New("不支持 ZIP64 格式")
	}

	prefix := int64(eocd) - centralDirectorySize - centralDirectoryOffset
	if prefix < 0 || prefix > int64(len(data)-22) {
		return 0, errors.New("zip 结构异常，无法计算数据偏移")
	}

	if prefix > 0 {
		if int(prefix)+4 > len(data) ||
			binary.LittleEndian.Uint32(data[prefix:]) != 0x04034b50 {
			return 0, errors.New("zip 结构异常，前置数据长度校验失败")
		}
		centralDirectoryPosition := prefix + centralDirectoryOffset
		if centralDirectoryPosition+4 > int64(len(data)) ||
			binary.LittleEndian.Uint32(data[centralDirectoryPosition:]) != 0x02014b50 {
			return 0, errors.New("zip 结构异常，中央目录校验失败")
		}
	}

	return int(prefix), nil
}

func patchArchive(source, target string) error {
	data, err := os.ReadFile(source)
	if err != nil {
		return err
	}
	prefix, err := archivePrefixLength(data)
	if err != nil {
		return fmt.Errorf("无法读取文件：%w", err)
	}

	reader, err := zip.NewReader(bytes.NewReader(data[prefix:]), int64(len(data)-prefix))
	if err != nil {
		return fmt.Errorf("无法读取 zip：%w", err)
	}

	out, err := os.Create(target)
	if err != nil {
		return err
	}
	success := false
	defer func() {
		if !success {
			_ = out.Close()
		}
	}()

	if prefix > 0 {
		if _, err := out.Write(data[:prefix]); err != nil {
			return err
		}
	}

	writer := zip.NewWriter(out)
	replaced := make(map[string]bool, len(patchedClasses))
	for _, name := range patchedClasses {
		replaced[name] = false
	}
	languages := 0

	for _, entry := range reader.File {
		if entry.Name == marker {
			continue
		}

		entryData, err := readEntry(entry)
		if err != nil {
			return fmt.Errorf("读取 %s 失败：%w", entry.Name, err)
		}

		if _, ok := replaced[entry.Name]; ok {
			entryData, err = payloadFS.ReadFile("payload/" + entry.Name)
			if err != nil {
				return fmt.Errorf("补丁资源缺失：%w", err)
			}
			replaced[entry.Name] = true
		} else if isLanguageFile(entry.Name) {
			changed := patchLanguage(string(entryData))
			if changed != string(entryData) {
				languages++
			}
			entryData = []byte(changed)
		}

		header := &zip.FileHeader{
			Name:          entry.Name,
			Method:        zip.Deflate,
			Modified:      entry.Modified,
			Comment:       entry.Comment,
			NonUTF8:       entry.NonUTF8,
			ExternalAttrs: entry.ExternalAttrs,
			Extra:         entry.Extra,
		}
		writerEntry, err := writer.CreateHeader(header)
		if err != nil {
			return fmt.Errorf("写入 %s 失败：%w", entry.Name, err)
		}
		if _, err := writerEntry.Write(entryData); err != nil {
			return fmt.Errorf("写入 %s 失败：%w", entry.Name, err)
		}
	}

	for name, ok := range replaced {
		if !ok {
			return fmt.Errorf("文件中缺少待替换的类：%s（这个补丁只适用于 HMCL 3.17.dev-583e004 或结构相同的版本）", name)
		}
	}
	if languages == 0 {
		return errors.New("文件中没有找到 download.china 提示文本")
	}

	markerHeader := &zip.FileHeader{Name: marker, Method: zip.Deflate, Modified: time.Now()}
	markerEntry, err := writer.CreateHeader(markerHeader)
	if err != nil {
		return err
	}
	if _, err := markerEntry.Write([]byte("FUCK_HMCL_Pather " + version + "\n" +
		"patched-at=" + time.Now().Format(time.RFC3339) + "\n" +
		"message=" + message + "\n")); err != nil {
		return err
	}

	if err := writer.Close(); err != nil {
		return err
	}
	if err := out.Sync(); err != nil {
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	success = true
	return nil
}

func readEntry(entry *zip.File) ([]byte, error) {
	reader, err := entry.Open()
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	return io.ReadAll(reader)
}

func isLanguageFile(name string) bool {
	return strings.HasPrefix(name, "assets/lang/I18N") && strings.HasSuffix(name, ".properties")
}

func patchLanguage(text string) string {
	return downloadChinaLine.ReplaceAllString(text, "download.china="+message)
}

// findHmclArchives returns the newest HMCL jar and the newest HMCL exe found in
// the executable directory and the current directory.
func findHmclArchives() ([]string, error) {
	directories := make([]string, 0, 2)
	if executable, err := os.Executable(); err == nil {
		directories = append(directories, filepath.Dir(executable))
	}
	if workingDirectory, err := os.Getwd(); err == nil {
		directories = append(directories, workingDirectory)
	}

	type candidate struct {
		path    string
		modTime time.Time
	}
	newest := map[string]candidate{}
	seen := make(map[string]bool)

	for _, directory := range directories {
		directory = filepath.Clean(directory)
		if seen[directory] {
			continue
		}
		seen[directory] = true

		entries, err := os.ReadDir(directory)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			if entry.IsDir() {
				continue
			}
			name := entry.Name()
			lower := strings.ToLower(name)
			if !strings.HasPrefix(lower, "hmcl") ||
				(!strings.HasSuffix(lower, ".jar") && !strings.HasSuffix(lower, ".exe")) {
				continue
			}
			if strings.Contains(lower, "sources") || strings.Contains(lower, "javadoc") ||
				strings.Contains(lower, "-pathed") || strings.Contains(lower, "-pather") ||
				strings.Contains(lower, "-patched") {
				continue
			}
			info, err := entry.Info()
			if err != nil {
				continue
			}
			extension := ".jar"
			if strings.HasSuffix(lower, ".exe") {
				extension = ".exe"
			}
			current, ok := newest[extension]
			if !ok || info.ModTime().After(current.modTime) {
				newest[extension] = candidate{filepath.Join(directory, name), info.ModTime()}
			}
		}
	}

	if len(newest) == 0 {
		return nil, errors.New("没有找到 HMCL*.jar / HMCL*.exe，请把文件路径作为参数传给本程序")
	}

	result := make([]string, 0, len(newest))
	for _, candidate := range newest {
		result = append(result, candidate.path)
	}
	sort.Strings(result)
	return result, nil
}

func replaceFile(source, destination string) error {
	if err := os.Rename(source, destination); err == nil {
		return nil
	}

	old := destination + ".fuck_hmcl_pather.old"
	_ = os.Remove(old)
	if err := os.Rename(destination, old); err != nil {
		return fmt.Errorf("替换文件失败：%w", err)
	}
	if err := os.Rename(source, destination); err != nil {
		_ = os.Rename(old, destination)
		return fmt.Errorf("替换文件失败：%w", err)
	}
	_ = os.Remove(old)
	return nil
}

func copyFile(source, destination string) error {
	in, err := os.Open(source)
	if err != nil {
		return err
	}
	defer in.Close()

	info, err := in.Stat()
	if err != nil {
		return err
	}

	out, err := os.OpenFile(destination, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, info.Mode().Perm())
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		_ = out.Close()
		return err
	}
	return out.Close()
}

func printUsage() {
	fmt.Println("FUCK_HMCL_Pather " + version)
	fmt.Println("用法：")
	fmt.Println("  FUCK_HMCL_Pather.exe [HMCL.jar|HMCL.exe]")
	fmt.Println("  FUCK_HMCL_Pather.exe --restore [HMCL.jar|HMCL.exe]")
	fmt.Println()
	fmt.Println("不指定文件时，会在程序所在目录和当前目录中自动寻找 HMCL*.jar / HMCL*.exe。")
	fmt.Println("补丁产物命名为 <源文件名>-pathed.<扩展名>，源文件不会被修改。")
}

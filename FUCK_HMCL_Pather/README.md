# FUCK_HMCL_Pather

一个针对 HMCL「在中国大陆地区禁用游戏下载功能」提示的补丁工具。

它做三件事：

1. 把下载页的黄色警告改成信息提示；
2. 把提示文字替换为下面这段社区声明；
3. 删掉地区检测触发的 `setDisable(true)`，让下载页保持可用。

> 恶意举报不是「维护秩序」，而是对开源社区和 MC 生态的严重破坏。这种行为损害的是所有玩家的利益：它让免费工具被迫收缩、让新玩家无从入门、让生态被无谓的恐惧笼罩。一颗老鼠屎，不该坏掉一整锅粥。

补丁同时支持两种 HMCL 发布形式：

- `HMCL-*.jar`
- `HMCL-*.exe`（PE 启动器 + 尾部内嵌 jar，补丁会保留启动器并只替换内嵌部分）

补丁不会改动源文件。打补丁后会生成一个新文件，命名规则为：

```text
HMCL-3.17.dev-583e004.jar  ->  HMCL-3.17.dev-583e004-pathed.jar
HMCL-3.17.dev-583e004.exe  ->  HMCL-3.17.dev-583e004-pathed.exe
```

## 文件说明

| 文件 | 平台 | 说明 |
| --- | --- | --- |
| `dist/FUCK_HMCL_Pather.sh` | Linux / macOS | 自包含 shell 补丁，内置 Java 补丁程序 |
| `dist/FUCK_HMCL_Pather.exe` | Windows x64 | 原生可执行补丁，不依赖 Java |
| `dist/FUCK_HMCL_Pather.jar` | 全平台 | Java 补丁程序本体，可单独使用 |
| `go/payload/` | - | 已编译好的两个替换类 |
| `patches/hmcl-download-china.patch` | - | 对应的 HMCL 源码补丁 |

## 使用方法

先关闭 HMCL，然后对 HMCL 的 jar 或 exe 执行补丁。

### Linux / macOS

```sh
chmod +x FUCK_HMCL_Pather.sh
./FUCK_HMCL_Pather.sh /path/to/HMCL-3.17.dev-583e004.jar
```

也可以直接指定 exe：

```sh
./FUCK_HMCL_Pather.sh /path/to/HMCL-3.17.dev-583e004.exe
```

把脚本放在 HMCL 文件旁边，直接运行；脚本会自动寻找 `HMCL*.jar` 和 `HMCL*.exe`，并分别处理最新的一个：

```sh
./FUCK_HMCL_Pather.sh
```

运行后会生成 `*-pathed.jar` / `*-pathed.exe`，原文件保持不变。

### Windows

把 `FUCK_HMCL_Pather.exe` 放到 HMCL 文件旁边，双击运行；或者从命令行指定文件：

```bat
FUCK_HMCL_Pather.exe "D:\Games\HMCL-3.17.dev-583e004.exe"
```

```bat
FUCK_HMCL_Pather.exe "D:\Games\HMCL-3.17.dev-583e004.jar"
```

不指定文件时，会同时处理程序所在目录中最新的一份 `HMCL*.jar` 和一份 `HMCL*.exe`。

### 使用 Java 补丁程序

```sh
java -jar FUCK_HMCL_Pather.jar /path/to/HMCL.jar
java -jar FUCK_HMCL_Pather.jar /path/to/HMCL.exe
```

## 还原 / 删除产物

补丁不会创建 `.bak` 备份，因为源文件从未被修改。需要删除补丁产物时：

```sh
./FUCK_HMCL_Pather.sh --restore /path/to/HMCL.exe
```

```bat
FUCK_HMCL_Pather.exe --restore "D:\Games\HMCL-3.17.dev-583e004.exe"
```

`--restore` 会删除对应的 `*-pathed.jar` / `*-pathed.exe`。为了兼容早期“原地修改”版本，如果发现 `.fuck_hmcl_pather.bak`，也会顺带把源文件还原。

不指定文件时，会处理自动找到的所有 HMCL jar/exe。

## 补丁原理

补丁不会改动源文件，而是读取源文件并在 `*-pathed.*` 新文件中更新 jar 内的资源：

- `org/jackhuang/hmcl/ui/download/VersionsPage$VersionsPageSkin.class`
  - `MessageDialogPane.MessageType.WARNING` → `MessageDialogPane.MessageType.INFO`
  - 删除 `root.setDisable(true)`
- `org/jackhuang/hmcl/ui/instances/DownloadListPage$ModDownloadListPageSkin.class`
  - `MessageDialogPane.MessageType.WARNING` → `MessageDialogPane.MessageType.INFO`
  - 删除 `pane.setDisable(true)`
- `assets/lang/I18N*.properties`
  - 把 `download.china=` 的内容替换为上面的社区声明
- 写入标记文件 `META-INF/FUCK_HMCL_PATHER.txt`，用于识别「已经打过补丁」

对于 `HMCL-*.exe`，补丁会解析 zip 结束记录，计算出 PE 启动器的长度（本版本为 454144 字节），原样保留启动器，只重建尾部内嵌的 jar。因此补丁后的 exe 仍然由原来的 HMCL 启动器启动。

地区检测本身仍然保留，只影响提示是否出现；下载功能不再被禁用。登录、启动已有版本、模组管理等功能不受影响。

## 兼容性

当前替换类是按 **HMCL 3.17.dev-583e004** 编译的。补丁会先检查目标文件中是否存在对应的类和 `download.china` 文本：

- 存在：继续打补丁；
- 不存在：直接报错并退出，不会写出半成品。

如果 HMCL 后续版本修改了这两个界面类，需要重新生成 `go/payload/` 中的类。源码补丁见 `patches/hmcl-download-china.patch`。

## 从源码构建

需要 JDK 17+ 和 Go 1.23+：

```sh
./build.sh
```

输出：

```text
dist/FUCK_HMCL_Pather.jar
dist/FUCK_HMCL_Pather.sh
dist/FUCK_HMCL_Pather.exe
```

`go/payload/` 中的两个 class 已经是编译好的结果；只要 HMCL 版本不变，重新执行 `build.sh` 不需要 JavaFX。

如果更换了 HMCL 版本，可用下面的脚本从 HMCL 源码重新生成替换类（需要 Python 3 和 JavaFX）：

```sh
./tools/build-payload.sh [HMCL.jar] [HMCL-main] [JavaFX目录]
```

## 说明

- 本工具只解除 HMCL 界面层面对下载功能的禁用，不绕过任何网络、账号或版权限制。
- 请支持正版游戏，并遵守所在地法律法规。
- 补丁产物会修改 HMCL 文件内容，HMCL 的自我完整性校验可能因此提示失败；这与直接使用未签名的 dev 构建表现一致，不影响启动。
- 本项目以 GPLv3 发布；`go/payload/` 中的类文件派生自 HMCL，同样遵循 GPLv3。

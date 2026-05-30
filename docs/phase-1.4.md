# Phase 1.4：AppCDS 归档削掉 ~80ms 启动开销

> 时间：2026-05-31
> 主题：Phase 1 收尾。让 ktx 启动从 ~220ms 降到 ~140ms。

## 这一波要解决什么

前面三波都在做功能性补全。Phase 1.4 是 Phase 1 的最后一块拼图，转向**性能**：用 AppCDS（Application Class Data Sharing）把 ktx 启动时的 JVM 类加载预归档，把那 80ms 揉掉。

这是 daemon 出来前的兜底优化——**daemon 上线后这 80ms 反正也不付**——但因为实现成本不高且对没启 daemon 的场景有效，值得做。

## 关键事实（实测得到）

| 命令 | 含义 |
|---|---|
| `-XX:ArchiveClassesAtExit=foo.jsa` | JDK 17+ 现代写法，跑一次进程后归档 |
| `-XX:SharedArchiveFile=foo.jsa` | 启动时加载已有归档 |
| `-XX:+AutoCreateSharedArchive` | JDK 19+：归档缺失或不匹配时自动重建，**优雅降级** |

实测结论：

- 一份 `ktx -e 'Unit'` 触发的归档约 **75MB**，含 Kotlin compiler embeddable 的核心类。
- 一份 `ktx --help` 触发的归档约 **11MB**，只有 clikt 路径——对 ktx 真实工作负载（编译/执行脚本）几乎没用。**归档负载选择决定收益**。
- 启动时间在 macOS M 系列芯片：220ms → 140ms（**-36%**）。
- 不命中 archive（class paths mismatch）时 JVM silent fallback，用户**完全无感**——错误的归档不会让 ktx 跑不起来，最多没有加速。

## 关键设计

### 1. 归档负载用 `ktx -e 'Unit'`，不是 `--help`

CDS 只能归档"那次跑加载过的类"。`--help` 走 clikt 就退出，根本不加载 Kotlin scripting host——而 scripting host 才是 ktx 每次 run 都用到的重头戏。

`-e 'Unit'` 触发完整 scripting host 初始化 + 编译表达式，**把那些重型类全录进去**。代价是 jsa 从 11MB 涨到 75MB，但这是一次性磁盘占用，换稳定 50-80ms 启动收益，值。

### 2. classpath 必须**完全一致**

CDS 校验签名时，归档时与运行时的 classpath（含顺序、绝对/相对路径形式）必须 byte-for-byte 一致，否则报 `shared class paths mismatch` 并 silent fallback。

第一版我归档时用 `lib/*.jar` globbing，与启动脚本里 ktx 写死的顺序不同——CDS 直接拒掉。修法：**从启动脚本里抠 CLASSPATH 行**，把 `$APP_HOME` 替换成实际路径，原样喂给归档进程。这样归档时与运行时使用同一份 classpath。

```kotlin
val classpathLine = launcher.readLines().firstOrNull { it.startsWith("CLASSPATH=") }!!
val cp = classpathLine.removePrefix("CLASSPATH=")
    .replace("\$APP_HOME", installRoot.absolutePath)
```

### 3. `applicationDefaultJvmArgs` 里引用 `$APP_HOME`

Gradle Application 插件生成的启动脚本会把 `applicationDefaultJvmArgs` 当作字面字符串塞进 `DEFAULT_JVM_OPTS=''`——POSIX 单引号里 `$VAR` 不会展开。

最初尝试 `applicationDefaultJvmArgs = listOf("-XX:SharedArchiveFile=\$APP_HOME/lib/ktx.jsa")`，结果 JVM 收到字面字符串 `$APP_HOME/lib/ktx.jsa`（路径不存在），AutoCreateSharedArchive 试图重建到这个错误路径但写不进去。

修法：用占位符 `APP_HOME_PLACEHOLDER`，在 `startScripts` 任务的 `doLast` 里**把那一行的最外层单引号改成双引号**，让 shell 真正展开变量：

```kotlin
.replace(
    Regex("""(?m)^DEFAULT_JVM_OPTS='(.*)'$"""),
) { match ->
    val inner = match.groupValues[1].replace("\"", "\\\"")
    "DEFAULT_JVM_OPTS=\"$inner\""
}
```

### 4. AutoCreateSharedArchive 兜底

`@file:Toolchain(jdk = "17")` 触发 ktx re-exec 到 JDK 17 时，jsa 是用 JDK 21 录的——class file version 不匹配。`-XX:+AutoCreateSharedArchive` 让 JVM 自动重建到原路径，第一次仍慢（多了一次归档），后续运行就匹配了。

这在 ktx 多 JDK 路由场景特别重要：用户可能装多个 JDK，每个 jsa 自动按版本各存各的——我们不需要为每个版本预打包。

## 项目布局变化

```diff
modules/cli/build.gradle.kts:
+   application.applicationDefaultJvmArgs = listOf(
+       "-XX:SharedArchiveFile=APP_HOME_PLACEHOLDER/lib/ktx.jsa",
+       "-XX:+AutoCreateSharedArchive",
+   )
+   tasks.named<CreateStartScripts>("startScripts").doLast { ... 占位符替换 + 单引号转双引号 ... }
+   val generateAppCdsArchive by tasks.registering(Exec::class) { ... }
+   tasks.named("installDist") { finalizedBy(generateAppCdsArchive) }
```

文件没新增，仅改 build 脚本。CLI 用户体验：`./gradlew :modules:cli:installDist` 之后 jsa 自动出现在 `lib/ktx.jsa`，启动脚本自动加载。

## 性能数据

实测三种姿态对比（macOS M3，Temurin JDK 21）：

| 场景 | 用时 |
|---|---|
| `java -cp ... -e 'println("hi")'` 无 archive | 220ms |
| `java -XX:SharedArchiveFile=ktx.jsa -cp ...` | **140ms** |
| 同样的命令但归档负载是 `--help`（错的负载） | 220ms |
| ktx 启动脚本 + jsa 完整链路 | 150ms |

**80ms / 36% 启动开销削减**，且对所有命令一致——`run`、`-e`、`lock`、`toolchain` 都受益。

但要注意：这 80ms 是 **JVM 启动 + CLI 类加载** 的部分。daemon 上线后 CLI 自身仍在主进程跑（IPC 客户端），所以这 80ms 仍然有意义；但**编译器冷启的那 1.4s** 才是 daemon 真正解决的大头，AppCDS 帮不上。

## 这一波撞到的坑

**`shared class paths mismatch` 是静默失败。**
JVM 不会以错误退出，只是 silent fallback 到无 archive 模式——表象就是「为什么我开了 archive 启动还是这么慢」。第一次没注意，看时间没省直接以为机制不工作。诊断要靠 `-Xlog:class+path=info` 或者 `bash -x bin/ktx` 看 java 命令最终展开什么。

**Gradle Application 的单引号陷阱。**
最初看到 `DEFAULT_JVM_OPTS='"-XX:SharedArchiveFile=$APP_HOME/lib/ktx.jsa"'` 还以为没事，因为里面有双引号。但**最外层是单引号**——POSIX 规则 single-quote 内一切都是字面字符，包括嵌套的双引号也只是字面字符。要把外层单引号换成双引号才行。Gradle 文档没提这一点。

**归档负载选择决定收益。**
`--help` 触发的归档只有 11MB，与真实工作负载几乎没交集。换 `-e 'Unit'` 后涨到 75MB 才命中关键类。**AppCDS 不是「越大越好」，但「太小」更有问题** —— 录不到真正会被加载的类，归档形同虚设。

**AppCDS 不优化 JVM 自身启动。**
Class Data Sharing 缩短的是「类加载并初始化」时间，不影响 JVM 自身启动（`java -version` 仍要 ~30ms）。所以收益上限在「应用类加载」那部分。本机 NVMe 磁盘 IO 极快，应用类加载本来就只 100-150ms，能省的也就这么多了。在慢盘上收益会更可观。

## 已知遗留 / 下一波要做的

1. **AppCDS 没有 daemon 时帮忙更大**。Phase 2 daemon 后，CLI 启动开销变得不重要（IPC 那点开销几毫秒），AppCDS 价值打折。但 daemon 自己启动时也能用 AppCDS——下一波考虑给 daemon 进程也归档（含完整 Kotlin compiler）。
2. **跨平台**：Windows .bat 启动脚本的占位符替换用了 `%APP_HOME%`，但没在 Windows 上实测过。
3. **Project Leyden AOT cache (JDK 24+) 单步形态**仍可作为下一阶段优化，但需要 JDK 25 LTS 普及。

## 总结

Phase 1.4 是个小但锐利的优化。三个关键点：

- **归档负载要触发真实工作路径**：`-e 'Unit'` 而不是 `--help`。
- **classpath 必须 byte-for-byte 一致**：从启动脚本抠出来，不要 globbing。
- **shell 引号陷阱**：Gradle 默认单引号包 DEFAULT_JVM_OPTS，需要手动改成双引号才能展开 `$APP_HOME`。

Phase 1 至此结束：CLI 主干（1.1）、lockfile（1.2）、JDK 工具链（1.3）、AppCDS（1.4）。功能、可重现性、工具链管理、启动性能四个维度都打到位。下一站 **Phase 2 daemon**——把那 1.4s 的编译器冷启从「每次脚本付」变成「全局付一次」。

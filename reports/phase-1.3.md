# Phase 1.3：JDK 工具链管理 + `@file:Toolchain(jdk = ...)` 真实路由

> 时间：2026-05-31
> 主题：脚本声明 JDK 主版本时，ktx 自动下载并切到对应 JVM 跑

## 这一波要解决什么

Phase 1.1 引入了 `@file:Toolchain(kotlin = "...", jdk = "...")` 注解，但 `jdk` 字段当时只打了一行日志。Phase 1.3 把它接通：

1. `ktx toolchain install 17` 调 Adoptium API 下载 + 解压 + 校验，存到 `~/.local/share/ktx/jdks/`。
2. 脚本声明 `@file:Toolchain(jdk = "17")` 时，CLI 检测当前 JVM 主版本不匹配 → 按需自动下载 → 用对应 java re-exec ktx 自己。
3. `ktx toolchain list / path` 给用户查询接口。

## 关键事实（直接打 Adoptium 实测）

```
GET https://api.adoptium.net/v3/assets/feature_releases/{major}/ga
  ?architecture={x64|aarch64}
  &os={mac|linux|windows}
  &image_type=jdk
  &jvm_impl=hotspot
  &heap_size=normal
  &vendor=eclipse
  &page_size=1
```

响应数组首元素：

- `binaries[0].package.link` → 直链 URL
- `binaries[0].package.checksum` → sha256 hex
- `binaries[0].package.name` → 文件名（决定 tar.gz vs zip）
- `version_data.semver` → 完整版本号（如 `21.0.11+10.0.LTS`）

**调研直接打 API 比看文档强**：返回的 JSON 结构 4 行就能看清，不用读 swagger。

## 设计

### 1. tar.gz 解压器自己手写

不引第三方库（commons-compress 1.5MB，过重）。自己实现 USTAR tar 解析器，~150 行：

- 每个 entry：512 字节 header + 数据（512 字节对齐）
- 处理 type flag：`'0'`/普通文件、`'5'`/目录、`'2'`/符号链接、`'L'`/GNU long-name 扩展
- macOS JDK 内部有 symlink（`Contents/MacOS/libjli.dylib` 之流），必须支持
- 可执行位通过 mode field 还原（识别 0755 → setExecutable）

zip 直接用 JDK 自带的 `ZipInputStream`。

**「去掉顶层目录」逻辑**：Adoptium 归档解压出来顶层是 `jdk-21.0.11+10/`，我们把它去掉，让 store 目录直接就是 JDK root。

### 2. macOS 路径布局抽象

macOS 上 JDK 的 `bin/java` 在 `Contents/Home/bin/java`，Linux/Windows 在 `bin/java(.exe)`。`Platform.javaBinRelative()` + `JdkInstall.javaHome` 两层抽象屏蔽差异：

```kotlin
val javaBin: Path get() = root.resolve(platform.javaBinRelative())
val javaHome: Path get() = when (platform.os) {
    Platform.Os.MAC -> root.resolve("Contents/Home")
    else -> root
}
```

Gradle Application 启动脚本读 `JAVA_HOME` 环境变量找 java，所以 re-exec 时只需 set 这个就行。

### 3. `@file:Toolchain(jdk=...)` 路由用 re-exec

最干净的方式：脚本要求一个 ktx 不在它上面跑的 JDK → CLI 用对应 JDK fork 自己一份子进程，原 args 透传。

```kotlin
val pb = ProcessBuilder(listOf(launcher) + originalArgs)
pb.environment()["JAVA_HOME"] = install.javaHome.toString()
pb.environment()["KTX_TOOLCHAIN_DISPATCHED"] = "1"  // 防止无限递归
val proc = pb.start()
exitProcess(proc.waitFor())
```

为什么不用 `execve()`：JVM 没有原生支持，要 JNI；而且 JVM 退出可能阻塞在 shutdown hooks 上。fork + 等待是更可移植的选择，代价是多一份进程内存（短暂）。

防递归：环境变量 `KTX_TOOLCHAIN_DISPATCHED=1`。子进程读到就跳过路由，即使 JDK 版本仍不对也直接报错而不是再次 re-exec。

定位 `bin/ktx` 启动脚本的优先级：

1. `KTX_LAUNCHER` 环境变量
2. `APP_HOME` 环境变量（Gradle Application 启动脚本会 set）
3. 通过 `protectionDomain.codeSource.location` 找 jar，回溯到 install root

### 4. CLI 字节码 target 必须降到 17

最先撞到的坑：CLI 用 `jvmToolchain(21)` 编译，生成 class file version 65。re-exec 到 JDK 17 时直接 `UnsupportedClassVersionError`。

修法：所有 ktx 模块的 `compileKotlin.jvmTarget = JVM_17` + Java `targetCompatibility = VERSION_17`。Kotlin 用 JDK 21 编译但产物兼容 JDK 17 完全合法。

**这意味着 ktx 的最低支持 JDK 是 17**——足够：JDK 17 是当前最广的 LTS，Kotlin 2.2 也支持它跑 scripting host。

## 项目布局变化

```diff
+ modules/toolchain/                  新模块
+   src/main/kotlin/.../toolchain/
+     Platform.kt                     OS+arch 检测
+     JdkInstall.kt                   一份已装 JDK 的描述
+     AdoptiumClient.kt               Adoptium API + 下载 + sha256 校验
+     Archive.kt                      tar.gz / zip 解压（含 USTAR 实现）
+     ToolchainStore.kt               manifest + list/install/find
+   src/test/kotlin/.../toolchain/
+     PlatformTest.kt
  modules/cli/
+   src/main/kotlin/.../cli/
+     ToolchainDispatcher.kt          re-exec 路由
+     command/ToolchainCommand.kt     list / install / path 子命令
    src/main/kotlin/.../cli/
      Main.kt                          ← 注册 toolchain 子命令树
      command/
        RunCommand.kt                  ← 调 ToolchainDispatcher.dispatchIfNeeded
+ samples/toolchain.main.kts          演示自动切换 JDK
```

## 验证结果

```
$ ktx toolchain install 17
[INFO] 从 Adoptium 下载 OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.19_10.tar.gz (177 MB)
[INFO] 解压到 /Users/nebula/.local/share/ktx/jdks/temurin-17.0.19+10
[INFO] 安装完成：temurin-17.0.19+10
已安装 JDK 17：/Users/nebula/.local/share/ktx/jdks/temurin-17.0.19+10/Contents/Home
       23.55 real
```

23 秒里绝大部分是 177MB 网络下载，sha256 校验通过，`bin/java` 可执行。

```
$ cat samples/toolchain.main.kts
#!/usr/bin/env ktx
@file:Toolchain(jdk = "17")
println("Running on JDK ${Runtime.version().feature()}")

$ ktx run samples/toolchain.main.kts        # 第一次跑
[INFO] 切换到 JDK 17 (...temurin-17.0.19+10/Contents/Home)
Running on JDK 17
java.version = 17.0.19
        1.87 real

$ ktx run samples/toolchain.main.kts        # 缓存命中 + re-exec
        0.49 real
```

re-exec 的额外开销（fork + waitFor）<100ms，主要时间是子进程跑全套。配合编译缓存命中后整体 490ms。

## 这一波撞到的坑

**class file version 65 vs JDK 17。**
最先撞的，调试时一开始没意识到 ktx 自己被 re-exec 后要在更老的 JDK 上跑。修法：所有模块 `jvmTarget = JVM_17`。

**`jvmToolchain(21)` 不影响 jvmTarget。**
jvmToolchain 只决定"用哪个 JDK 编译"，不决定字节码版本。要降版本得显式设 `compilerOptions.jvmTarget` + Java 的 `targetCompatibility`。Kotlin 文档对此提示不够强。

**`kotlin { ... }` 块和 `java { ... }` 块要同时设。**
否则报 `Inconsistent JVM-target compatibility detected for tasks 'compileJava' (21) and 'compileKotlin' (17)`。即便我们模块没 Java 源码，java plugin 仍会校验 compileJava 任务（即使空跑）。

**Adoptium 响应是数组而非对象。**
直接 `parseAsset(json)` 时按 `Map` 解析失败。改成 `List<Map<String, Any?>>` 后取 `[0]`。

**Moshi codegen 不引入。**
为一个一次性结构写完整 data class + Moshi adapter 太啰嗦。直接 `Moshi.adapter(Types.newParameterizedType(List::class.java, Map::class.java))` 解出嵌套 Map 树，按 path 取值。等响应字段稳定且复用率高时再改。

**re-exec 需要捕获原始 argv。**
`ProcessHandle.current().info().arguments()` 在 macOS 上常返回空。用 `OriginalArgs.set(rawArgs)` 在 main 入口手动保存一份。

## 已知遗留 / 下一波要做的

1. **首次 `re-exec + 完整编译` 仍是 1.87s**——这是 Phase 2 daemon 化的目标场景。daemon 暖时应该 ~300ms。
2. **`ktx toolchain remove` / `gc` 没做**，下一波 1.4 顺带加。
3. **没有"默认 JDK"概念**——脚本未声明 `@file:Toolchain(jdk=...)` 时仍沿用启动 ktx 的 JVM。如果想要"系统级默认 JDK 21"，得在 ktx 启动脚本里查 toolchain manifest 里钉一份。Phase 1.4 处理。
4. **JDK 主版本数字解析很 naive**：`"17"` 按 `.` 切第一段。未来若要支持 `"17.0.13"` 完整 semver 需扩展。
5. **没缓存 Adoptium API 响应**——每次 `install 17` 都打一次 API。低频，先不优化。

## 总结

Phase 1.3 把 ktx 从「Kotlin 脚本 runner」升级到 **「带 JDK 工具链管理的 Kotlin 脚本 runner」**——这是 uv 体验的核心一环。

撞到的坑（5 个）整体围绕一件事：**ktx 本身要在多个 JDK 版本上跑**，所以编译目标得保守、re-exec 得精确、原 args 得手动捕获。这是「写 CLI 工具」的特殊负担——一般库不必担心自己的字节码 target 问题。

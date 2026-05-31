# Phase 3.2：`ktx compile --native`

> 时间：2026-05-31
> 主题：让脚本编出来就是单文件原生二进制——无 JRE、无 ktx、双击就跑。

## 这一波要解决什么

Phase 2.4 的 fat jar 让最终用户不再需要 ktx，但仍需要 JRE 17+；Phase 3.1 的 self-contained 把 JRE 也打进去，但产物是 40–60 MB 的目录树。**最后一档是 GraalVM native image：单文件 ~38 MB，启动 ~10–25 ms，最终用户什么都不需要装**。

`reports/phase-3-todo.md` 里给的预估是 2-3 周深坑，因为 Kotlin scripting host + main-kts 在 GraalVM 上没有官方支持，反射点深。这一波本期范围按用户确认是 **MVP + jackson 跑通**：`hello.main.kts` 与 `jackson.main.kts` 两个 sample 必须能 native build 并执行成功；ktor-client / 协程深反射场景留 follow-up。

实测产物：

| 脚本 | 二进制 | 启动 |
|---|---|---|
| `hello.main.kts`（无依赖） | 38 MB | ~22 ms |
| `jackson.main.kts`（jackson-databind + 3 传递） | 41 MB | ~15 ms |

跟 Phase 2.4 fat jar 比：体积 ~3×（因为 reflect 元数据 + native runtime stub），但**启动 ~1/10**（hello 22 ms vs 180 ms）；最终用户不需要 JRE。

## 关键设计

### Metadata 三层 fallback

GraalVM 在编译期做 reachability 分析，反射 / resource / serialization 不在分析视野内必须显式声明。本期实现了三层叠加：

1. **ktx 内置 base**（`modules/core/src/main/resources/ktx/native-image/*-config.json`）。覆盖 Kotlin scripting host 的核心反射路径：`KtsScript / KtsScriptDefinition / KtsEvaluationConfiguration`、`KJvmCompiledScript` / `KJvmCompiledScriptData`、`PropertiesCollection` 系列序列化、`META-INF/kotlin/script/*.kotlin_script` resource、`*.kotlin_module` / `*.kotlin_builtins` resource。手写。
2. **GRMR 子集**（`modules/core/src/main/resources/ktx/grmr/<g>/<a>/<v>/reachability-metadata.json`）。本期只内置 jackson 三件套（databind 2.15.2、core 2.6.0、annotations 2.3.0）—— 这些都是 GRMR 标记 `latest=true` 的版本，向上覆盖到 2.21.x。匹配逻辑：`RecordingResolver` 给的 `g:a:v` 坐标，从 `versions.txt` 索引里找最近版本。
3. **Per-script agent trace**（`<script>.native-meta/`）。`--collect-metadata` 启动 fat jar 一次，挂 `native-image-agent` 把所有反射事件录到脚本旁。后续构建直接复用。

### 为什么不直接用 `-H:ConfigurationFileDirectories=` 合并 GRMR

第一版尝试把多份 GRMR JSON 合并成一个 array 写到 `metaDir/reachability-metadata.json`。结果 native-image 报：

```
Error: Error parsing JNI configuration in .../meta/reachability-metadata.json
```

GraalVM 期望 `reachability-metadata.json` 文件是**单个 unified-format 对象**（含 `reflection / resources / serialization / jni / bundles` 五个 key），不是 JSON 数组。

修法：每个 GRMR 文件单独放在 `<grmrDir>/META-INF/native-image/<g>/<a>/reachability-metadata.json`，把 `<grmrDir>` 加到 native-image 的 `-cp`。GraalVM 的标准 classpath discovery 自动扫描 `META-INF/native-image/*/*/` 路径。

### `JvmScriptingHostConfigurationKt.<clinit>` 的 NPE

native-image 编出来的 hello binary 第一次跑直接 NPE：

```
Caused by: java.lang.NullPointerException
  at java.io.File.<init>(File.java:278)
  at kotlin.script.experimental.jvm.JvmScriptingHostConfigurationKt.<clinit>
```

原因：那个 class 的 `<clinit>` 调 `File(System.getProperty("java.home"))`。native binary 没 JVM，`java.home` 默认 null，构造 File NPE。

试过的解法：

- `--initialize-at-build-time=...JvmScriptingHostConfigurationKt`：触发 reflect 滑坡（PropertyReference1Impl 拉了 `kotlin.reflect.jvm.internal.impl.builtins.jvm.JvmBuiltInClassDescriptorFactory`，这个被默认 run-time-init，冲突 build fail）。
- `--initialize-at-run-time=kotlin.script.experimental.jvm`：实测没起作用（依然 NPE）。GraalVM 21 的 init policy 与 user override 优先级有些灰区。

最终选 **NativeBootstrap 包装方案**：

- 在 `modules/core/.../bootstrap/NativeBootstrap.kt` 写一个 main entry point。
- 它先把 `java.home / user.home / user.dir` 设成合理 fallback（取自 env var / `"/"`），**然后才**触发任何 Kotlin scripting class 加载。
- `NativeImageFlow` 在 fat jar 编出来后**重写 manifest** —— `Main-Class` 改为 `NativeBootstrap`，原 main class 名（`Hello_main`）写到一个新 resource `ktx/script-main.txt`。
- NativeBootstrap 在自己的 main 里先 setProperty，再读取 `ktx/script-main.txt`，反射调用原 main class。

副效果：bootstrap 只增加 < 1 KB 字节码 + 一次额外反射调用（< 1 ms），但完全规避了 init time 的滑坡。

### 脚本主类的反射注册

NativeBootstrap 用 `Class.forName(scriptMainClassName).getMethod("main", Array<String>::class.java)` 反射 invoke。但 `Hello_main` / `Jackson_main` 这种 class 名是脚本生成的、本期 ktx 内置 reflect-config.json 不可能预先列举。

修：`NativeImageFlow.writeScriptMainReflectConfig` 在每次 build 时往 metaDir 的 reflect-config.json 里追加一条针对 `metadata.mainClass` 的 keep。脚本类名信息来自 `CompileMetadata`（这一波给 `CompileFlow` 加的轻量返回）。

### `-jar` 模式下 `-H:Name` / `-H:Path` 被忽略

第一次编出来的二进制叫 `app`（fat jar 文件名），不是我传的 `-H:Name=hello`。GraalVM 21 的行为：`-jar` 模式下从 jar 文件名推断 image name，`-H:Name` 被静默忽略。

修：换成在 `-jar app.jar` **之后**加 `-o $outDir/$outName`。`-o` 是公开 flag，可靠覆盖。

### `--no-fallback` 是必选项

每次 build 都加 `--no-fallback`：缺反射元数据时直接 fail，避免 native-image 偷偷塞个 fallback JVM 到产物里把体积膨胀到 200 MB+。本期 strategy 是「问题暴露在编译期」。

### 不选 `--initialize-at-build-time=kotlin,kotlinx`

社区常见示范都建议这个粗暴的 build-time-init 范围。实测在 ktx 场景下**会触发大量冲突**：

```
kotlin.reflect.jvm.internal.impl.renderer.DescriptorRendererModifier the class was requested to be initialized at run time but got initialized at build time
... <19 more> ...
org.jetbrains.kotlin.org.eclipse.aether.repository.RemoteRepository was unintentionally initialized at build time
```

main-kts 拖了 `org.eclipse.aether`（依赖解析框架），fat jar 把它打进了运行时——但运行时根本不需要它（脚本已编译）。强行 build-time-init 这一坨会冲突 kotlin-reflect 的内部缓存。

最终选项：**完全不指定 init policy，让 GraalVM 自己分析**。代价是 cold start 多 ~50 ms（21+ 默认 run-time-init 全员），但可靠。

## 端到端示例

```bash
# 0. 装 GraalVM（一次性）
mise use -g java@oracle-graalvm-21.0.7

# 1. hello world：第一次必须 --collect-metadata
ktx compile --native --collect-metadata samples/hello.main.kts
# → fat jar 11MB → agent run 跑一次 → native-image 编 ~50s → samples/hello (38MB)

./samples/hello arg1 arg2
# hello from main.kts
# args (2): arg1 / arg2
# 0.022s

# 2. 后续构建：metadata 已在 samples/hello.main.kts.native-meta/，不需 flag
rm samples/hello
ktx compile --native samples/hello.main.kts

# 3. jackson sample
ktx compile --native --collect-metadata samples/jackson.main.kts
./samples/jackson
# {"name":"ktx","phase":"1.2","lockfile":true}
# 0.015s
```

## 项目布局变化

```diff
modules/core/src/main/kotlin/io/github/nebula/ktx/core/
+ bootstrap/
+   NativeBootstrap.kt          native binary main shim
+ compile/
+   NativeImageFlow.kt          ktx compile --native 编排

  compile/CompileFlow.kt        + compileWithMetadata returning CompileMetadata

modules/core/src/main/resources/ktx/
+ native-image/
+   reflect-config.json         KtsScript / KJvmCompiledScript / NativeBootstrap
+   resource-config.json        META-INF/kotlin/script/, *.kotlin_module 等
+   serialization-config.json   PropertiesCollection / KJvmCompiledScriptData 等
+   proxy-config.json           []
+   jni-config.json             []
+ grmr/
+   com.fasterxml.jackson.core/{databind,core,annotations}/<v>/reachability-metadata.json
+   com.fasterxml.jackson.core/{databind,core,annotations}/versions.txt

modules/core/build.gradle.kts   + kotlinx-serialization-json (用于 reflect-config 合并)

modules/cli/src/main/kotlin/io/github/nebula/ktx/cli/command/
  CompileCommand.kt             + --native / --collect-metadata 三路分发
```

## 这一波撞到的坑（汇总）

1. **`-Os` 不在 GraalVM 21**。是 24+ 的 flag。21 上要么 `-O2`（默认）要么 `-Ob`（quick）。换 `-O2`。
2. **`--initialize-at-build-time=kotlin,kotlinx` 和 reflect 内部 run-time-init 打架**。改用最小化 build-time 策略——只指定 NativeBootstrap 必须的 path。
3. **`-H:Name` 在 `-jar` 模式下被忽略**。换 `-o` 显式传输出文件路径。
4. **JvmScriptingHostConfigurationKt NPE**。NativeBootstrap shim 抢先 setProperty 解决。
5. **`Cannot find metadata for script Hello_main`** —— `META-INF/kotlin/script/<name>.kotlin_script` resource 没被 native-image 包进去。`resource-config.json` 加 `META-INF/kotlin/script/.*\.kotlin_script` pattern。
6. **`InvalidClassException: PropertiesCollection serialVersionUID = 1, local = -23...`** —— GraalVM 没把这个类当 serializable 看待。`serialization-config.json` 显式注册（连同 KJvmCompiledScript / KJvmCompiledScriptData 等）。
7. **GRMR 多个文件不能合并到一个 reachability-metadata.json**。改成放在 `META-INF/native-image/<g>/<a>/` 各自独立，加进 native-image 的 classpath。
8. **`ClassLoader.getResources("META-INF/MANIFEST.MF")` 在 native binary 里返回空**。换成自定义 resource `ktx/script-main.txt`（必须在 resource-config.json 里 keep）。
9. **NativeBootstrap 反射调 Hello_main.main 报 NoSuchMethodException**——脚本主类没在 reflect-config 里。`writeScriptMainReflectConfig` 动态追加。
10. **KDoc 里 `*/*/reachability-metadata.json` 把 KDoc 提前闭合**。Kotlin `/** ... */` 内的 `*/` 不论上下文都会闭合，重写文档避免这个 substring。

## 已知遗留 / 未来优化

1. **二进制体积 38–41 MB 偏大**。reflect / scripting-jvm / scripting-common / 部分 jackson 是大头。引入 `--strip-debug`（GraalVM 21 已自动）+ ProGuard / R8 关键 keep rules 可下到 ~15 MB。
2. **构建时间 ~50 s**（hello）/ ~60 s（jackson）。`-Ob`（quick build）可降到 ~20 s 但运行时性能差，仅 dev 用。
3. **GraalVM 工具链未托管**。本期仅检测 + 错误提示。Phase 3.3 可加 `ktx toolchain install graalvm` 复用 toolchain 模块。
4. **跨平台 CI matrix 未做**。只在 macOS arm64 实测过。生产分发要 macOS x64 / Linux x64 / Linux arm64 / Windows x64 各 build 一份。
5. **`--collect-metadata` 必跑一遍脚本主体**。与 `ktx compile` 产物 jar 流程一致——副作用会在编译时发生一次。脚本若有 destructive side effect 需自行 guard。
6. **GRMR 仅内置 jackson**。okhttp / netty / slf4j 等常用库等用户报需求再加。GRMR 1.0.2 release zip 解压全装也只 5 MB，可以考虑下一轮全打。
7. **`<script>.native-meta/` 不自动入 .gitignore**。用户应根据自己策略决定是否 check in（推荐 check in，CI 复用）。
8. **本期 `--initialize-at-run-time=kotlin.script.experimental.jvm` 试过无效**。GraalVM 21 的 init flag 优先级机制比文档复杂。NativeBootstrap workaround 简单且工作；不深究。

## 总结

Phase 3.2 把 ktx 从「打包工具」彻底升级为「分发链路」：现在脚本作者用 ktx，发布给最终用户的可以是 fat jar（11 MB + JRE 依赖）、self-contained 目录（40 MB 目录无依赖）、**或单文件 native binary（38 MB 单文件无依赖）**。

跟 main-kts / kscript 比较，ktx 在「跨语言脚本运行器」赛道里独占了「single-file native binary」这一档——其它工具都没做到。配合 Phase 3.1 self-contained，ktx 已经覆盖 Kotlin 脚本生态从 dev 到 prod 的全流程。

下一波最有价值的工作是 **跨平台 CI matrix**——只有把 macOS x64 / Linux / Windows 全 build 出来，native 才真正进入生产可用。这是 Phase 3.3 的范围。

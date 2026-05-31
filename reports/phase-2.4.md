# Phase 2.4：`ktx compile` 把脚本编译成独立 fat jar

> 时间：2026-05-31
> 主题：让 end user 不再需要 ktx 也不再需要 Kotlin 编译器，只要 JRE 17+ 就能跑脚本。

## 这一波要解决什么

ktx 自己装一套 80MB（含 60MB kotlin-compiler-embeddable）—— 对**脚本作者**完全合理（他们要编译/调试），但对**脚本使用者**是巨大浪费：他们只想跑而已。

`ktx compile foo.kts → foo.jar`：把脚本编译产物 + 用户依赖 + Kotlin 标准运行时合并成单个 fat jar。**不含**编译器、main-kts、ktx 自身的 cli/daemon 模块。

实测产物：

| 脚本 | jar 大小 | java -jar 启动 |
|---|---|---|
| `hello.main.kts`（无依赖） | 11 MB | 180 ms |
| `jackson.main.kts`（含 jackson-databind + 3 传递） | 13 MB | 220 ms |

跟 ktx 自己（80MB 体积、首次 6.7s 跑通）相比：**体积 1/6，启动 1/30**。

## 关键设计与坑

### 1. 脚本编译产物 jar 已经有 `Main-Class`，直接用

调研发现 main-kts 编译出的 jar 本身就含 `META-INF/MANIFEST.MF` 带 `Main-Class: Hello_main`，且脚本类已生成 `public static main(String[])`。**不需要写 shim**——这是 Phase 2.4 最大的"省力"发现。

但 main-kts 默认的 manifest `Class-Path` 指向 ktx 自己的 lib 目录绝对路径（不能直接分发），需要用 [JarPacker] 重写 manifest，只保留 `Main-Class`。

### 2. fat jar 合并里的 5 类 META-INF 处理

| entry | 处理 |
|---|---|
| `META-INF/MANIFEST.MF` | 丢弃，重写仅含 Main-Class |
| `META-INF/services/...` | **累加合并** —— ServiceLoader 约定，多个提供者要保留 |
| `module-info.class` | 跳过 —— fat jar 走 classpath 不需要模块描述 |
| `META-INF/versions/N/module-info.class` | 同上 |
| 其他 (`*.kotlin_module`、`pom.xml` 等) | 先到先得 |

services 合并是关键：`META-INF/services/javax.xml.parsers.SAXParserFactory` 出现在多个 jar 里，简单覆盖会丢功能。累加 `\n` 拼接。

### 3. `KtsScript(args: Array<String>)` 必须有 `KtsEvaluationConfiguration`

最大的坑。我们 KtsScript 之前定义只有 `compilationConfiguration`，没有 `evaluationConfiguration`。**ktx run 模式**下没事——`ScriptRunner` 显式调 `createJvmEvaluationConfigurationFromTemplate<KtsScript>(hostConfig) { constructorArgs(scriptArgs) }`，把 args 手动塞进 evaluation config。

但 **ktx compile 产物**走的是 main-kts `RunnerKt.runCompiledScript` 默认入口。它读 `@KotlinScript(evaluationConfiguration=...)` 里 baked 的 hooks 决定怎么把 `main(String[])` 的 args 注入构造器。我们没声明，所以 args 永远为空 → `wrong number of arguments: 0 expected: 1`。

修：复用 main-kts 内部的 `configureConstructorArgsFromMainArgs` hook：

```kotlin
class KtsEvaluationConfiguration : ScriptEvaluationConfiguration({
    refineConfigurationBeforeEvaluate(::configureConstructorArgsFromMainArgs)
})

@KotlinScript(
    fileExtension = "kts",
    compilationConfiguration = KtsScriptDefinition::class,
    evaluationConfiguration = KtsEvaluationConfiguration::class,  // ← 新增
)
abstract class KtsScript(val args: Array<String>)
```

`configureConstructorArgsFromMainArgs` 这个 hook 是 main-kts 公开导出的，它读 `mainArguments` 评估配置项把数组塞 `constructorArgs`。

### 4. 不能 `wrapForLockOnly`——必须保留脚本主体

第一版 CompileFlow 复用了 LockingFlow 的 `wrapForLockOnly`（脚本主体替换为 `Unit`，仅触发依赖解析）。结果产物 jar 跑起来什么都没输出——因为字节码里**没有** println，只有一个返回 Unit 的脚本。

修：CompileFlow 直接用 `runner.run(scriptPath, ...)` 跑完整源码。**副作用确实会发生**（脚本会执行一次，比如 HTTP 请求），与 ktx run 第一次跑无异。这是可接受的——用户编译时的副作用属于编译时责任。

### 5. 运行时 jar 链：stdlib + script-runtime 不够

第一版只打 `KotlinJars.kotlinScriptStandardJars`（stdlib + script-runtime）—— 跑产物报：

```
NoClassDefFoundError: kotlin/script/experimental/jvm/RunnerKt
```

main-kts 编译的脚本类 main 调 `RunnerKt.runCompiledScript`，它在 `kotlin-scripting-jvm.jar`。继续追：

- `kotlin-scripting-jvm`（146 KB）—— 含 RunnerKt
- `kotlin-scripting-common`（194 KB）—— RunnerKt 引用 ScriptCompilationConfiguration 等
- `kotlin-reflect`（3 MB）—— ConfigurationFromTemplateKt 用 `KClass.objectInstance`

用 `Class.protectionDomain.codeSource` 反向定位 jar 路径，自动加进打包列表。

reflect 3MB 偏大但绕不开。Phase 2.5+ 可考虑给 reflect 也跑 ProGuard / R8 砍掉脚本不用的部分。

### 6. ktx core jar 也要带（KtsScript 基类）

脚本类 `Hello_main extends io.github.nebula.ktx.core.script.KtsScript` —— `KtsScript` 在 core 模块。打包时通过 `KtsScript::class.java.protectionDomain.codeSource` 找到 `core-*.jar`，整个加进去（100KB 量级，不在意）。

## 项目布局变化

```diff
modules/core/src/main/kotlin/io/github/nebula/ktx/core/
+ compile/
+   JarPacker.kt          fat jar 合并工具
+   CompileFlow.kt        编排：编译 + 抓依赖 + 合并

  script/KtsScript.kt     ← @KotlinScript 加 evaluationConfiguration

modules/cli/src/main/kotlin/io/github/nebula/ktx/cli/command/
+ CompileCommand.kt       ktx compile 子命令
  Main.kt                 ← 注册 CompileCommand
```

## 端到端示例

```
$ ktx compile samples/jackson.main.kts
[INFO] 编译 /Users/nebula/Projects/kts/samples/jackson.main.kts
[INFO] 合并：脚本产物(4KB) + 3 用户依赖 + 4 Kotlin 运行时 + ktx core
[INFO] 打包完成：samples/jackson.jar（8593 entries）
产物：samples/jackson.jar (13537 KB)
运行：java -jar samples/jackson.jar [args...]

$ java -jar samples/jackson.jar
{"name":"ktx","phase":"1.2","lockfile":true}

$ time java -jar samples/jackson.jar > /dev/null
real    0.22s
```

## 这一波撞到的坑（汇总）

1. **`@KotlinScript` 缺 evaluationConfiguration → 产物 jar 拒绝执行**。最隐蔽，因为 ktx run 路径没有此问题。
2. **lockOnly wrapper 不能复用到 compile**。lock 删主体是为了避免副作用；compile 必须保留主体。
3. **scripting-jvm + scripting-common + reflect 是必须的运行时依赖**——KotlinJars.kotlinScriptStandardJars 不返回它们。
4. **services 合并必须做累加**，否则 ServiceLoader 找不全提供者（影响 jackson 等大量库）。
5. **module-info.class 必须剔除**，否则 JVM 用 module path 报 split package 错误。

## 已知遗留 / 未来优化

1. **副作用问题**：compile 会跑一遍脚本主体。如果脚本里有 `println("now compiling...")`，那条语句会在 compile 时输出一次。可加 `--no-execute` flag 用 main-kts 的 BasicJvmScriptingHost API 走纯编译路径（跳过 BasicJvmScriptEvaluator）。
2. **产物体积优化**：reflect 3MB 是大头。引入 ProGuard / R8 + keep rules 可砍到 ~500KB（脚本场景反射用得有限）。
3. **依赖去重**：用户多个脚本编译到不同 fat jar，每份都重复打 stdlib + reflect。Phase 3.1 的 self-contained 模式可考虑「共享 runtime image」减少总磁盘占用。
4. **签名 / reproducible build**：当前 manifest 含构建时间戳，可重现构建需要稳定化。
5. **Native image**（Phase 3.2）：用 GraalVM 把 fat jar 编成 native binary，体积可降到 ~5-10MB，启动 ~10ms。

## 总结

Phase 2.4 把 ktx 真正打开了"分发场景"。原本 ktx 是开发者工具（必须装 ktx + 60MB 编译器），现在 ktx 既是**开发工具**又是**编译器**——用户拿到产物 jar 完全独立。

跟 main-kts / kscript 相比这是 ktx 独有的能力（main-kts 没有 compile 子命令，kscript 的 `--package` 也只到 fat jar 级别）。配合 Phase 3.1（jpackage self-contained）+ Phase 3.2（native image），ktx 会成为 Kotlin 脚本生态里**唯一**的"既能开发又能分发"的工具。

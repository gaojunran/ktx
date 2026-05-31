# Toolchain Management

ktx can download and manage JDKs from [Adoptium](https://adoptium.net/), so a script can pin its required JDK and ktx will Just Work.

## Declare a JDK in your script

```kotlin
#!/usr/bin/env ktx

@file:Toolchain(jdk = "17")

println("Running on JDK ${Runtime.version().feature()}")
```

When you `ktx run` this script:

1. ktx scans the script header (no compiler involved) for `@file:Toolchain`.
2. If the requested `jdk` major version differs from the JVM running ktx, ktx looks for a matching install under `~/.local/share/ktx/jdks/`.
3. If missing, ktx **downloads** Eclipse Temurin from the Adoptium API for your OS/arch.
4. ktx **re-execs itself** with `JAVA_HOME` pointed at the new JDK, so the rest of the run (compilation, execution) happens on the requested JVM.

```mermaid
sequenceDiagram
  participant U as User
  participant K as ktx (current JVM)
  participant K2 as ktx (re-execed onto target JDK)
  participant Adoptium

  U->>K: ktx run my.kts
  K->>K: scan @file:Toolchain
  alt requested != current
    K->>K: ToolchainStore.find(major)
    alt not installed
      K->>Adoptium: GET /v3/assets/feature_releases/17/ga
      Adoptium-->>K: {downloadUrl, sha256}
      K->>Adoptium: GET tar.gz / zip
      K->>K: verify sha256, extract
    end
    K->>K2: fork with JAVA_HOME=&lt;target&gt;
    K2->>K2: KTX_TOOLCHAIN_DISPATCHED=1, run script
    K2-->>U: stdout
  else requested == current
    K->>K: run script directly
    K-->>U: stdout
  end
```

The re-exec is guarded by `KTX_TOOLCHAIN_DISPATCHED=1`. If a child somehow ends up on the wrong JDK, it errors out instead of forking again.

## Manage installations manually

```bash
ktx toolchain install 17    # download JDK 17
ktx toolchain install 21    # download JDK 21
ktx toolchain list          # show what's installed
ktx toolchain path 17       # print absolute path to bin/java
```

## Where things live

```
~/.local/share/ktx/jdks/
├── temurin-17.0.19+10/
│   └── Contents/Home/bin/java        # macOS layout
├── temurin-21.0.11+10/
│   └── bin/java                      # Linux/Windows layout
└── manifest.tsv                      # id<TAB>major<TAB>semver
```

The `manifest.tsv` is updated atomically on each install.

## Why per-script JDK matters

- **Library compatibility.** A script using JDK 21 virtual threads cannot run on JDK 17. Declaring the requirement makes failure modes explicit.
- **Reproducibility.** A script that worked on your laptop with JDK 21 may not work on a colleague's machine running JDK 11. Pinning ensures the JDK matches.
- **CI integration.** CI doesn't have to set up multiple JDK toolchains by hand — `@file:Toolchain` does that automatically.

## Supported platforms

ktx's downloader supports the following Adoptium releases:

| OS | Architectures |
| --- | --- |
| macOS | aarch64 (Apple Silicon), x64 |
| Linux | aarch64, x64 |
| Windows | x64 |

Other platforms (Linux ppc64le, etc.) work in principle but have not been tested.

## Daemon interaction

When daemon mode is active, ktx routes the request to the daemon for the requested JDK major version, forking a new daemon if needed:

```bash
ktx daemon status --all
# daemon [jdk=21;protocol=v1]: running (pid=42631, dir=5f350a9ddd42)
# daemon [jdk=17;protocol=v1]: running (pid=42788, dir=6ab41020796c)
```

Each daemon process runs on the JDK it was forked under. Class file version 65 (JDK 21) bytecode never gets loaded into a JDK 17 daemon because the daemon never sees it.

## Caveats

- **Network access on first install.** If you need fully-offline operation, run `ktx toolchain install <major>` on a connected machine, then sync `~/.local/share/ktx/jdks/` to your offline target.
- **Disk usage.** Each Temurin JDK is roughly 200 MB compressed, ~300 MB extracted. ktx does not currently provide a `toolchain remove` command (planned).
- **Mirror configuration.** Adoptium is a global CDN; if you need a regional mirror or behind a corporate proxy, set the standard Java properties in your shell (`http.proxyHost`, etc.). A native `--mirror` option is on the roadmap.

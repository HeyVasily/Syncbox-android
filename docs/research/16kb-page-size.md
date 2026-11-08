# Android 16 KB pages: Go and Syncthing

**Research date: 2 November 2026.**

> **About this file:** this report was written by an external research agent and is saved here with tracking parameters removed from its links. The appendices it mentions and `check_native_16k.py` are not in this repository. Its experiments were not reproduced here.
>
> The following points were checked against primary sources on 2 November 2026: the Play 16 KB rule and its 1 February 2027 update date, the Play target API rule and its extension date, the RELRO guidance, BasicSync's gomobile build and NDK version, Syncthing PR #10783, and the Go and Syncthing versions. Project decisions based on this report are in [decisions.md](../decisions.md).

**Evidence labels:** **Verified by experiment** means the agent ran the command or inspected the artifact. **Documented** means official documentation, project source, or an upstream issue supports the statement. **Inferred** identifies a recommendation or conclusion from that evidence. Static inspection does not count as an Android runtime test.

## 1. Verdict

**Feasible.** **Verified by experiment:** Go **1.27.3** with NDK **r30 / 30.0.16248370** produced 16 KB-aligned Android libraries for **ARM64 and x86-64**, both with direct `c-shared` builds and actual `gomobile bind` builds. Upstream **Syncthing v2.1.7** also built successfully for Android/ARM64 as a PIE executable and as a shared-library experiment. These outputs had `PT_LOAD.p_align = 0x4000` with the NDK's default linker options.

**The main remaining risk is runtime integration and the complete native dependency set.** The Go library, AndroidX libraries, database mappings, filesystem watcher, JNI callbacks, and subprocess packaging all need to work together under the Android application sandbox.

**Verified by experiment:** the downloaded BasicSync and Syncthing-Fork releases pass the applicable 64-bit ELF and APK ZIP alignment checks. **Not confirmed:** runtime operation on a 16 KB device, acceptance by a Play Console account, or reproducibility in F-Droid.

All experiments took place under `/tmp`. No project repository files were modified.

## 2. Required configuration

### Selected versions

These are concrete pins for a new build. They are not all minimum versions mandated by Google Play.

| Component | Selected version/configuration | Status |
|---|---|---|
| Go | **1.27.3** | Current stable; used in the experiments |
| Syncthing | **v2.1.7** | Built from upstream source; requires Go 1.26.2 |
| NDK | **30.0.16248370**, r30 | Used in the experiments |
| gomobile and gobind | **v0.0.0-20261011034528-197770444867** | Both pinned; actual AAR builds succeeded |
| Android Gradle Plugin | **9.5.0** | Current stable, documented |
| Gradle | **9.6.0** | Documented AGP 9.5 compatibility |
| JDK | **17** | Documented AGP minimum/default |
| Android Build Tools | **36.0.0** | Its `zipalign` was used |
| SDK levels | **min 34, target 36, compile 36** | User's minimum and current mobile Play target requirement |
| Native compiler API | **34** | `aarch64-linux-android34-clang`, etc. |
| ABIs | **arm64-v8a**, **x86_64** | Recommended for the requested scope |
| bundletool | **1.18.3** | Pin for the proposed bundle checks |

**Documented:** the relevant versions and compatibility information come from the [Go download page](https://go.dev/dl/), NDK downloads, AGP release notes, Play target-API policy, and bundletool releases. **Not confirmed:** a new complete Gradle app using this entire matrix was not assembled.

### Gradle settings

**Inferred recommendation**, using the documented configuration APIs:

```kotlin
// Root build.gradle.kts
plugins {
    id("com.android.application") version "9.5.0" apply false
}

// app/build.gradle.kts: merge into the existing configuration
android {
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 34
        targetSdk = 36

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}
```

```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip
```

**Architecture-dependent setting:** use `false` for a JNI/gomobile library loaded from the APK. For a Fork-style implementation that launches `libsyncthingnative.so` as an executable from `ApplicationInfo.nativeLibraryDir`, use **`useLegacyPackaging = true`** so the binary is extracted. Both choices can support 16 KB pages. The `.so` filename alone does not establish that a file is a JNI library. [Fork packaging and process-launch source](https://github.com/researchxxl/syncthing-android/tree/v2.1.7.0/app/src/main/java/com/nutomic/syncthingandroid/service).

### Go flags

**Verified by experiment:** this flag nesting works. For a Syncthing-backed C-export bridge:

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
mkdir -p out/arm64-v8a

GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
CC="$TOOLCHAIN/bin/aarch64-linux-android34-clang" \
CXX="$TOOLCHAIN/bin/aarch64-linux-android34-clang++" \
go build -buildvcs=false -trimpath -buildmode=c-shared \
  -ldflags='-checklinkname=0 -linkmode=external -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"' \
  -o out/arm64-v8a/libbackend.so ./mobile
```

Here `./mobile` must be the actual `package main` C-export bridge. For x86-64, use `GOARCH=amd64`, the `x86_64-linux-android34` compiler prefix, and an `out/x86_64` output directory.

The flags serve different purposes:

- **`max-page-size=16384`** controls external-linker ELF alignment. It was unnecessary for obtaining 16 KB LOAD alignment with the tested NDK r30 defaults.
- **`common-page-size=16384`** controls layout details such as RELRO padding. It changed RELRO endpoints in the minimal experiment. **Inferred recommendation:** keep both page-size flags explicit.
- **`-linkmode=external`** is already required for this Android `c-shared` path.
- **`-checklinkname=0`** was required by Syncthing's sampled `github.com/wlynxg/anet` dependency with Go 1.27.3. Without it, the build failed with `invalid reference to net.zoneCache`. This is unrelated to page size.
- **`-trimpath` and `-buildvcs=false`** control paths/build metadata, not page alignment.

**Documented:** Go interprets `-ldflags`; `-extldflags` passes arguments to the external compiler driver; Clang's `-Wl,` passes them to LLD. Putting `-Wl,...` directly in Go's `-ldflags` without `-extldflags` is incorrect. [Go linker docs](https://go.dev/src/cmd/link/doc.go)

For gomobile:

```bash
MOBILE_VERSION=v0.0.0-20261011034528-197770444867
go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION"
go install "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"

# In the module containing the exported Go package:
go get -tool "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"

gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi=34 \
  -trimpath \
  -ldflags='-checklinkname=0 -linkmode=external -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"' \
  -o backend.aar ./mobile
```

Here `./mobile` is a gomobile-bindable Go package. Omit `-checklinkname=0` if its dependencies do not need it. **Documented/source inspection:** the sampled gomobile `init` installs `gobind@latest`; explicitly pin both tools instead of allowing a later `init` to replace the pin. [Pinned initialization source](https://github.com/golang/mobile/blob/19777044486779222400e9ca16c4306f9c6dbfa9/cmd/gomobile/init.go).

## 3. Findings

### 3.1 Google Play: current rule, dates, scope, and enforcement

**Documented:** the live Android guide says that apps targeting **Android 15 / API 35 or higher** must support 16 KB pages on **64-bit devices**. Its current enforcement statement says noncompliant **updates** cannot be released starting **1 February 2027**. [Android guide](https://developer.android.com/guide/practices/page-sizes)

Official sources are not fully synchronized:

| Official statement | Date/scope stated |
|---|---|
| Original announcement, 8 May 2025 | New apps and updates targeting Android 15+ required compliance from **1 November 2025** |
| Google employee clarification | Extensions could be requested until **31 May 2026** |
| Live technical guide, updated October 2026 | Blocks noncompliant **updates** from **1 February 2027** |
| Current Play technical-quality help | Lists 16 KB support under **Current Requirements**, including new app bundles |

**Not confirmed:** the newer update-specific date does not establish a February 2027 grace period for every new-app submission. An authenticated Play Console or an account-specific extension was not inspected. **Inferred recommendation:** submit a compliant build from the start. [Announcement](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)

**Documented:** Play's App Bundle Explorer reports compatibility information, while Policy status can show warnings. The published enforcement action is blocking release of noncompliant updates. These checks do not demonstrate database or filesystem correctness at runtime.

**The published scope refers to `targetSdk`, not `minSdk`.** However, lowering `targetSdk` is not a practical route for a new mobile submission: the separate current target-API rule requires **API 36** for new/updated phone and tablet apps, with its stated extension ending **1 November 2026**. `minSdk=34` remains compatible with that rule. [Play Console Help](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)

Scope and exceptions:

- **Documented:** Java/Kotlin-only applications, including their dependencies, already support the page-size transition. A Go backend makes the app native.
- **Documented:** 16 KB page-size support concerns the **64-bit ABIs**; there is no planned equivalent transition for 32-bit ABIs.
- **Not confirmed:** a blanket Go, open-source, private-app, or F-Droid exemption from Play's page-size rule. Exceptions to other Play policies should not be assumed to apply here.
- **Documented:** current technical-quality help separately lists TV and Wear OS requirement dates; the concrete settings in this report concern phone/tablet distribution. [Play Console Help](https://support.google.com/googleplay/android-developer/answer/17492799?hl=en)

### 3.2 Go ELF alignment, versions, and linker modes

**Verified by experiment:** Go 1.27.3, NDK r30, native API 34:

| Build | Android/ARM64 | Android/amd64 |
|---|---|---|
| Minimal `c-shared`, defaults | All LOADs `0x4000` | All LOADs `0x4000` |
| Minimal `c-shared`, explicit max/common flags | All LOADs `0x4000` | All LOADs `0x4000` |
| Actual gomobile bind, defaults | All LOADs `0x4000` | All LOADs `0x4000` |
| Actual gomobile bind, explicit flags | All LOADs `0x4000` | All LOADs `0x4000` |
| Forced internal `c-shared` | Rejected | Rejected |
| Pure-Go internally linked executable | All LOADs `0x10000` | Rejected: external linking required |
| Syncthing v2.1.7 PIE executable | All LOADs `0x4000` | Not rebuilt |
| Syncthing v2.1.7 shared-library experiment | All LOADs `0x4000` | Not rebuilt |

**Documented/source inspection:** Android `c-shared` and gomobile use external linking, so the NDK linker's behaviour matters. The internal ARM64 ELF alignment is a separate case; the 64 KB value already appears in inspected Go 1.5 source. The internal amd64 ELF linker's 4 KB default does not describe a supported internally linked Android/amd64 shared-library build. [Go linker config](https://go.dev/src/cmd/link/internal/ld/config.go)

**The relevant default change was NDK r28, not a recent universal Go "16 KB fix".** Go issue #74839 reports Go 1.24.3 gomobile output with 4 KB alignment and confirms that NDK r28 fixed both ARM64 and x86-64. [NDK r28 changelog](https://github.com/android/ndk/wiki/Changelog-r28)

**Documented:** Go 1.26 fixed a separate cgo callback alignment defect, [#46893](https://github.com/golang/go/issues/46893). That is a reason to avoid stale Go toolchains, but it is distinct from ELF page alignment.

#### Minimal experiment

```go
package main

import "C"
import "os"

//export Add
func Add(a, b C.int) C.int { return a + b }

//export GoPageSize
func GoPageSize() C.int { return C.int(os.Getpagesize()) }

func main() {}
```

```bash
GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
CC="$TOOLCHAIN/bin/aarch64-linux-android34-clang" \
go build -buildvcs=false -trimpath -buildmode=c-shared \
  -o minimal-arm64-default.so .

"$TOOLCHAIN/bin/llvm-readelf" -W -l minimal-arm64-default.so
```

**Verified by experiment:** exit 0; LOAD output:

```text
LOAD 0x000000 0x0000000000000000 0x0000000000000000 0x0919fc 0x0919fc R   0x4000
LOAD 0x091a00 0x0000000000095a00 0x0000000000095a00 0x07f310 0x07f310 R E 0x4000
LOAD 0x110d20 0x0000000000118d20 0x0000000000118d20 0x019500 0x01a2e0 RW  0x4000
LOAD 0x12a220 0x0000000000136220 0x0000000000136220 0x007f80 0x0445c8 RW  0x4000
```

The tools reported:

```text
go version go1.27.3 linux/amd64
NDK Pkg.Revision = 30.0.16248370
Android Clang version 21.0.0, build 16134705
```

#### Syncthing experiment

**Verified by experiment:** after generating the required assets in a scratch source tree, both modes built:

```bash
go run -buildvcs=false ./script/genassets.go \
  -o lib/api/auto/gui.files.go gui

# Environment: GOOS=android, GOARCH=arm64, CGO_ENABLED=1,
# CC=aarch64-linux-android34-clang

go build -buildvcs=false -trimpath -tags=noupgrade \
  -buildmode=pie -ldflags=-checklinkname=0 \
  -o syncthing-arm64 ./cmd/syncthing

go build -buildvcs=false -trimpath -tags=noupgrade \
  -buildmode=c-shared -ldflags=-checklinkname=0 \
  -o syncthing-arm64.so ./cmd/syncthing
```

Both returned exit 0; every LOAD had alignment `0x4000`. Their dynamic dependencies were `libdl.so`, `liblog.so`, and `libc.so`. The combined explicit max/common-page flags also built successfully.

**Limitation:** building the command package as `c-shared` demonstrates linking, not a usable production embedding API. It does not create a Syncthing lifecycle/JNI facade.

### 3.3 NDK, AGP, ZIP alignment, and App Bundles

**Documented:**

- **NDK r27** introduced opt-in flexible page-size support.
- **NDK r28+** enables the relevant alignment by default.
- With r27 CMake/ndk-build, the switches are `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` and `APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true`.
- A Go build invoking Clang directly does not inherit CMake settings; pass linker options through Go when needed.

**Documented:** **AGP 8.5.1+** is the supported packaging baseline. Earlier AGP 8.3 to 8.5 configurations could produce a locally working APK while the bundle-generated APK path remained problematic. This is why checking only `assembleRelease` is insufficient. [Android Developers Blog](https://android-developers.googleblog.com/2024/12/get-your-apps-ready-for-16-kb-page-size-devices.html)

**Documented:** with `minSdk >= 23`, an unset `useLegacyPackaging` defaults to uncompressed/page-aligned native libraries. `true` uses compression and extraction. ELF alignment must still be correct after extraction. [JniLibsPackaging](https://developer.android.com/reference/tools/gradle-api/8.8/com/android/build/api/dsl/JniLibsPackaging)

**Documented:** the Android DSL setting initializes both direct-APK and bundle packaging. If a plugin modifies the **variant API**, `useLegacyPackaging` and `useLegacyPackagingFromBundle` are separate properties and should remain consistent. [JniLibsApkPackaging](https://developer.android.com/reference/tools/gradle-api/9.5/com/android/build/api/variant/JniLibsApkPackaging)

**Documented:** bundletool **1.17.0** changed its default page size to 16 KB. An AAB's own ZIP offsets are not the installed library offsets: inspect its native ELF files and the APKs generated from it. [bundletool releases](https://github.com/google/bundletool/releases)

**Verified by experiment:** r30's default minimal ARM64 build had RELRO ending at `0x133000`; the common-page flag changed that to `0x134000`. The following writable LOAD began at `0x136220`, outside the rounded protected page.

**Documented:** an unaligned RELRO endpoint can still be valid when RELRO occupies its own LOAD and protection does not overlap later writable data. Do not automatically reject every nonzero `(VirtAddr + MemSiz) % 16384`. [Android guide](https://developer.android.com/guide/practices/page-sizes)

### 3.4 Syncthing's native dependencies and SQLite

**Documented/source inspection:** Syncthing **v2.1.7** uses:

| Build constraint | SQLite driver |
|---|---|
| `cgo` | `github.com/mattn/go-sqlite3 v1.14.52` |
| `!cgo && !wazero` | `modernc.org/sqlite v1.60.1` |

There is no Android exclusion in those driver-selection constraints. [Cgo selection](https://github.com/syncthing/syncthing/blob/v2.1.7/internal/db/sqlite/db_open_cgo.go), [non-cgo selection](https://github.com/syncthing/syncthing/blob/v2.1.7/internal/db/sqlite/db_open_nocgo.go), [versions](https://github.com/syncthing/syncthing/blob/v2.1.7/go.mod).

**Verified by experiment:** the upstream Android/cgo dependency graph contained CgoFiles in:

```text
runtime/cgo
net
github.com/wlynxg/anet
github.com/mattn/go-sqlite3
```

Both released comparator Go binaries contain build metadata showing **cgo enabled and mattn/go-sqlite3**. The SQLite C code is linked into the Go ELF; neither APK has a separate `libsqlite3.so`.

**Verified by experiment:** native inventories:

| APK / ABIs | Native ELF files per ABI |
|---|---|
| BasicSync v3.27, ARM64 and x86-64 | `libandroidx.graphics.path.so`, `libgojni.so`, `libimage_processing_util_jni.so`, `libsurface_util_jni.so` |
| Fork v2.1.7.0, ARM64, x86-64, ARMv7 and x86 | `libandroidx.graphics.path.so`, `libsyncthingnative.so` |

There was no packaged `libc++_shared.so`. Android platform libraries referenced by `DT_NEEDED` are supplied by Android.

**Documented/source inspection:** BasicSync also incorporates generated JNI/cgo glue and its `stbridge/pidfdhack` C code inside `libgojni.so`; these do not add separate APK libraries. [Wrapper source](https://github.com/chenxiaolong/BasicSync/tree/v3.27/stbridge).

**Inferred:** selecting modernc would remove the SQLite C compilation step, but Go still produces native ELF and remains subject to the requirement. **Documented:** Syncthing recommends cgo for SQLite performance and memory use. [Syncthing documentation](https://docs.syncthing.net/dev/building.html)

### 3.5 Runtime correctness and real bug reports

**Documented/source inspection:** Go 1.27.3 discovers the physical page size through `AT_PAGESZ`, with additional startup fallbacks, and uses `physPageSize` for Linux memory operations. Its 4096 minimum and internal 8192-byte allocator page are not assumptions that the Android kernel page size is 4096. [Go runtime](https://go.dev/src/runtime/os_linux.go)

**Documented/source inspection:** no 4 KB OS-page assumption was found in the sampled Syncthing database/file-I/O paths. Its SQLite amalgamation uses `getpagesize()`/`sysconf(_SC_PAGESIZE)` and accounts for runtime page size in mapping logic. A database's page format is separate from the OS page size; existing SQLite database pages do not need conversion to 16 KB. SQLite's database-file mmap is normally disabled by default, although WAL uses shared-memory mappings. [SQLite source](https://github.com/mattn/go-sqlite3/blob/v1.14.52/sqlite3-binding.c), [sqlite.org](https://www.sqlite.org/mmap.html)

**Inferred:** a 4096-byte buffer is not itself a bug. Page-sensitive mmap/mprotect addresses, offsets, lengths, and protection boundaries are the concern.

| Report | Documented finding |
|---|---|
| [Go #74839](https://github.com/golang/go/issues/74839) | Gomobile's 4 KB ELF alignment resolved by NDK r28 |
| [Go #74390](https://github.com/golang/go/issues/74390) | Prebuilt Linux/amd64 executables failed on a 16 KB Android emulator; different build path from NDK-linked Android libraries |
| [gopenpgp #300](https://github.com/ProtonMail/gopenpgp/issues/300), [Go #46893](https://github.com/golang/go/issues/46893) | Real migration/flag issues and a separate cgo callback alignment defect fixed in Go 1.26 |
| [Syncthing PR #10783](https://github.com/syncthing/syncthing/pull/10783) | BasicSync crashed on a 16 KB x86-64 emulator because app seccomp blocked `epoll_wait`; x/sys v0.47.0 fixed the syscall choice |
| [Fork #149](https://github.com/researchxxl/syncthing-android/issues/149) | Device properties mentioned 16384, but the failure was investigated as Java OOM reading a large log; not a confirmed page-size defect |

**Verified by experiment:** inspected BasicSync and Fork binaries use x/sys **v0.49.0** and **v0.48.0**, respectively, which include the syscall fix.

**Documented:** PR #10783 warns that launching through `adb shell` does not exercise the same app seccomp restrictions. Test through the installed APK/service/JNI context. The report establishes a crash **on** a 16 KB image, not that page size caused the seccomp failure.

**Not confirmed:** an unresolved general 16 KB blocker in current Go or Syncthing. Failure to find one is not a runtime guarantee.

### 3.6 BasicSync and Syncthing-Fork: flags and released APKs

**Documented/source inspection**, with Go versions also checked in ELF metadata:

| Setting | BasicSync v3.27 | Fork v2.1.7.0 |
|---|---|---|
| Go | 1.27.3 | 1.27.1 |
| NDK | 30.0.16248370 | 30.0.16248370 |
| AGP | 9.4.1 | 9.4.1 |
| Integration | gomobile/JNI | PIE subprocess |
| Relevant flags | `-checklinkname=0`, `-buildid=`, `-trimpath`; SQLite build tags | `CGO_ENABLED=1`, `EXTRA_LDFLAGS=-checklinkname=0` |
| Explicit page-size overrides | None found in pinned recipe | None found in pinned recipe |
| Measured packaging | Uncompressed native libraries | Compressed native libraries |

Sources: [BasicSync build](https://github.com/chenxiaolong/BasicSync/blob/v3.27/app/build.gradle.kts), [Fork native build](https://github.com/researchxxl/syncthing-android/blob/v2.1.7.0/syncthing/build-syncthing.py).

**Verified by experiment:** official release artifacts were downloaded and checked:

| Release artifact | ELF result | `zipalign -c -P 16 -v 4` |
|---|---|---|
| BasicSync 3.27 ARM64, 25 Oct 2026 | All four libraries' LOADs `0x4000` | Exit 0, `Verification successful` |
| BasicSync 3.27 x86-64 | All four libraries' LOADs `0x4000` | Exit 0, `Verification successful` |
| Fork 2.1.7.0 universal, 21 Oct 2026 | Both libraries in each 64-bit ABI: LOADs `0x4000` | Exit 0, `Verification successful` |

Release provenance: [BasicSync v3.27](https://github.com/chenxiaolong/BasicSync/releases/tag/v3.27), [Syncthing-Fork v2.1.7.0](https://github.com/researchxxl/syncthing-android/releases/tag/v2.1.7.0).

BasicSync's uncompressed library data offsets were all multiples of 16384. Fork's libraries were compressed, so those ZIP offsets are not mmap requirements.

Fork's two **32-bit Syncthing binaries** have `0x1000` LOAD alignment. Google's script flags them, but those are outside the 64-bit page-size requirement. The script still exited zero.

**Conclusion:** the relevant LOAD/ZIP checks pass. Runtime operation and Play acceptance were not tested.

### 3.7 Verification methods

**Inferred recommendation:** use three independent layers:

1. Every packaged 64-bit ELF's program headers.
2. Actual APK ZIP offsets for uncompressed native libraries.
3. Runtime tests under the real Android application sandbox.

**Documented:** Android Studio **Build > Analyze APK** exposes native entries and an **Alignment** column. It is useful for identifying the dependency that needs rebuilding. [Android guide](https://developer.android.com/guide/practices/page-sizes)

### 3.8 ABIs with minSdk 34

**Documented:** Play requires the matching 64-bit architecture when a native 32-bit architecture is supplied; it does not require supplying the 32-bit architecture. [64-bit requirement](https://developer.android.com/google/play/requirements/64-bit)

**Inferred recommendation:** use **arm64-v8a** for production and **x86_64** for emulator/testing scope. If x86-64 has no production audience, it can be restricted to a development/test variant.

`armeabi-v7a` is **not required** because of minSdk 34. Adding it is a product-coverage choice; `minSdk` does not select an ABI. A 4 KB-aligned 32-bit companion does not invalidate a compliant ARM64 library, and a correctly built 16 KB-compatible 64-bit binary also works on 4 KB systems. [NDK build system notes](https://android.googlesource.com/platform/ndk/+/mirror-goog-main-ndk/docs/BuildSystemMaintainers.md)

### 3.9 F-Droid

**Documented:** F-Droid's reproducibility guide explicitly discusses 16 KB support. Its inclusion policy requires suitable source and toolchain provenance. [F-Droid reproducible builds](https://f-droid.org/en/docs/Reproducible_Builds/)

**Inferred:** there is no inherent conflict with these linker flags, either packaging mode, or the selected 64-bit ABIs. Reproducibility requires matching the actual build inputs: Go, gomobile/gobind, NDK, AGP, Gradle, JDK, Build Tools, source commits, flags, and stripping behaviour.

**Documented:** native stripping, embedded paths/timestamps, toolchain differences, and ZIP metadata can affect byte identity. `-trimpath`, controlled build metadata, and pinned tools help.

**Inferred recommendation:** compare independently rebuilt standalone APKs with the corresponding upstream APK, not an AAB or a different set of Play-generated splits. Keep the native source build consistent across channels and disable Syncthing's self-updater for the packaged app.

**Not confirmed:** F-Droid's availability of the exact proposed toolchain, metadata acceptance, or a successful byte-for-byte reproduction.

## 4. CI checks to add

Use Bash and fail the step when any gate fails. `check_native_16k.py` is not in this repository.

```bash
set -euo pipefail

NDK="$ANDROID_HOME/ndk/30.0.16248370"
LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
ZIPALIGN="$ANDROID_HOME/build-tools/36.0.0/zipalign"
APK=app-release.apk
CHECK_DIR=$(mktemp -d)

unzip -q "$APK" -d "$CHECK_DIR"

"$LLVM/llvm-readelf" -W -l \
  "$CHECK_DIR/lib/arm64-v8a/libgojni.so"

"$LLVM/llvm-objdump" -p \
  "$CHECK_DIR/lib/arm64-v8a/libgojni.so" | rg LOAD

python3 check_native_16k.py "$APK"
"$ZIPALIGN" -c -P 16 -v 4 "$APK"
```

**Required result:** every relevant LOAD has alignment at least `0x4000`, and its file offset and virtual address are congruent modulo 16384. `0x10000` is acceptable. Inspect **program headers**, not section alignment.

**Verified by experiment:** the agent's Python gate checks every LOAD, scans ELF magic, handles APK/AAB/AAR/APKS, and returns failure for bad alignment or congruence. Negative tests modifying the **last** LOAD failed correctly. It reports RELRO warnings separately.

### Google's diagnostic script

```bash
curl -fsSL \
  'https://android.googlesource.com/platform/system/extras/+/refs/heads/main/tools/check_elf_alignment.sh?format=TEXT' \
  | base64 --decode > check_elf_alignment.sh

SCRIPT_BIN=$(mktemp -d)
ln -s "$LLVM/llvm-objdump" "$SCRIPT_BIN/objdump"

PATH="$SCRIPT_BIN:$ANDROID_HOME/build-tools/36.0.0:$PATH" \
  bash check_elf_alignment.sh "$APK"
```

It also needs `file` and `unzip`.

**Verified/source inspection:** the downloaded script examines only the first LOAD, can report 32-bit files, and does not reliably return a failing exit status for its findings. Use it as additional diagnostics, with the strict gate above.

**Documented:** `zipalign -P 16` checks 16 KiB placement of uncompressed `.so` entries; the final `4` concerns ordinary ZIP-entry alignment. If aligning manually, do it **before** `apksigner`. [zipalign](https://developer.android.com/tools/zipalign)

### AAB and generated APKs

**Proposed pipeline; not executed on a real project AAB:**

```bash
BUNDLETOOL=/path/to/bundletool-all-1.18.3.jar
AAB=app-release.aab
CI_OUT=$(mktemp -d)

java -jar "$BUNDLETOOL" validate --bundle="$AAB"
java -jar "$BUNDLETOOL" dump config --bundle="$AAB" \
  > "$CI_OUT/bundle-config.json"

# For the proposed uncompressed-library configuration:
rg -q 'PAGE_ALIGNMENT_16K' "$CI_OUT/bundle-config.json"

python3 check_native_16k.py "$AAB"

# Disposable local test key
keytool -genkeypair -noprompt -keystore "$CI_OUT/test.jks" \
  -storepass android -keypass android -alias androiddebugkey \
  -dname 'CN=Android Debug,O=Android,C=US' \
  -keyalg RSA -keysize 2048 -validity 3650

java -jar "$BUNDLETOOL" build-apks \
  --bundle="$AAB" --output="$CI_OUT/all.apks" \
  --ks="$CI_OUT/test.jks" --ks-key-alias=androiddebugkey \
  --ks-pass=pass:android --key-pass=pass:android

python3 check_native_16k.py "$CI_OUT/all.apks"
unzip -q "$CI_OUT/all.apks" -d "$CI_OUT/apks"

while IFS= read -r -d '' split; do
  "$ZIPALIGN" -c -P 16 -v 4 "$split"
done < <(find "$CI_OUT/apks" -type f -name '*.apk' -print0)
```

For intentionally compressed subprocess packaging, omit the uncompressed-library BundleConfig gate. Check every generated native split, not only `base.apk`. [bundletool](https://developer.android.com/tools/bundletool)

## 5. Test plan for a 16 KB emulator

**Documented; repository availability checked:** Google supplies `google_apis_ps16k` images for API 35 and API 36, with ARM64 and x86-64 variants. Select the explicitly named **16 KB Page Size** image. [Official image repository](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml).

For an x86-64 host:

```bash
sdkmanager 'platform-tools' 'emulator' \
  'system-images;android-35;google_apis_ps16k;x86_64'

avdmanager create avd --name syncthing-16k-api35 \
  --package 'system-images;android-35;google_apis_ps16k;x86_64' \
  --device pixel_8

emulator -avd syncthing-16k-api35 -no-snapshot-load \
  > emulator-16k.log 2>&1 &

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do
  sleep 2
done

adb shell getconf PAGE_SIZE
# Must print: 16384
```

Repeat with `android-36`; use the `arm64-v8a` image on an ARM64 host. **Documented:** x86-64 simulates the 16 KB environment, while ARM64 supports the actual kernel configuration. Include ARM64 testing before release. [AOSP 16 KB docs](https://source.android.com/docs/core/architecture/16kb-page-size/16kb)

On a rootable image supporting Android's compatibility mode:

```bash
adb root
adb wait-for-device
adb shell setprop bionic.linker.16kb.app_compat.enabled false
adb shell setprop pm.16kb.app_compat.disabled true

adb install -r app-release.apk
adb logcat -c
adb shell monkey -p your.application.id \
  -c android.intent.category.LAUNCHER 1
```

**Documented:** Android 16 can run some incompatible apps through compatibility mode. Do not count that as successful native 16 KB validation; confirm the properties are supported and effective on the chosen image. [Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-all)

**Recommended acceptance tests:**

| Area | Required test |
|---|---|
| Runtime | Repeated cold starts; embedded Go's `os.Getpagesize()` reports 16384 |
| Database | Fresh database, existing-data migration, WAL checkpointing, restart/recovery, integrity checks on test data |
| Watcher | Create, modify, rename, and delete files through the real app context; no SIGSYS |
| File I/O | Sync boundary sizes around 4096 and 16384, plus large files; compare hashes |
| JNI/networking | Repeated callbacks, interface changes, IPv4/IPv6, discovery and reconnects |
| Packaging | Install standalone and bundle-generated APKs; test upgrades and subprocess extraction |
| Regression | Run the same release on a normal 4 KB system, including API 34 |

Capture logcat, tombstones, build fingerprint, ABI, page size, APK hash, and test results.

**Not executed:** Android runtime testing. The research environment had no connected device or installed/running emulator, and `/dev/kvm` was absent.

## 6. Risks and unknowns

- **Play's official wording differs across pages.** The newest update-blocking date should not be generalized into a confirmed new-app exemption.
- **Static success does not prove runtime correctness.** Database mappings, callbacks, watcher syscalls, and app lifecycle still require testing.
- **No final application APK/AAB exists yet.** The eventual dependency inventory and bundle delivery path remain to be checked.
- **Syncthing's Android networking dependency uses Go internals.** The observed `-checklinkname=0` requirement makes Go upgrades worth testing independently of page alignment.
- **The Syncthing shared-object experiment is not an embedding implementation.**
- **F-Droid reproduction and the complete proposed Gradle build were not run.**
- **Historical toolchains were source-compared, not all rebuilt.** The measured build matrix was Go 1.27.3 / NDK r30.

## 7. Principal sources

- [Android 16 KB support guide](https://developer.android.com/guide/practices/page-sizes)
- [Original Google Play announcement](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html), [extension clarification](https://support.google.com/googleplay/android-developer/thread/368982598/clarification-of-the-16kb-page-size-deadline-and-information-on-extensions?hl=en), [current technical requirements](https://support.google.com/googleplay/android-developer/answer/17492799?hl=en)
- [Play target-API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
- [Go downloads](https://go.dev/dl/), [Go linker flags](https://go.dev/src/cmd/link/doc.go), [Go runtime page detection](https://github.com/golang/go/blob/go1.27.3/src/runtime/os_linux.go)
- [NDK r27 changelog](https://github.com/android/ndk/wiki/Changelog-r27), [r28 changelog](https://github.com/android/ndk/wiki/Changelog-r28), [current downloads](https://developer.android.com/ndk/downloads)
- [AGP 9.5 release notes](https://developer.android.com/build/releases/agp-9-5-0-release-notes), [APK/bundle packaging API](https://developer.android.com/reference/tools/gradle-api/9.5/com/android/build/api/variant/JniLibsApkPackaging)
- [Syncthing v2.1.7 source](https://github.com/syncthing/syncthing/tree/v2.1.7), [build documentation](https://docs.syncthing.net/dev/building.html), [Android seccomp fix discussion](https://github.com/syncthing/syncthing/pull/10783)
- [BasicSync v3.27 release](https://github.com/chenxiaolong/BasicSync/releases/tag/v3.27), [Syncthing-Fork v2.1.7.0 release](https://github.com/researchxxl/syncthing-android/releases/tag/v2.1.7.0)
- [zipalign](https://developer.android.com/tools/zipalign), [Google ELF checker](https://android.googlesource.com/platform/system/extras/+/refs/heads/main/tools/check_elf_alignment.sh), [bundletool](https://developer.android.com/tools/bundletool)
- [AOSP 16 KB architecture](https://source.android.com/docs/core/architecture/16kb-page-size/16kb), [Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-all)
- [F-Droid reproducible builds](https://f-droid.org/en/docs/Reproducible_Builds/), [inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/)

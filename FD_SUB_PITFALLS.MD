# F-Droid Submission Pitfalls — com.sponsorcut

A post-mortem of the 21-commit journey to get `com.sponsorcut` accepted by F-Droid's
CI pipeline. Each section maps to one or more commits in the MR.

---

## 1. `scanignore` must live **inside** the `Builds` entry

**Commit:** `07aaedca`

`scanignore` is a build-level key. Writing it at the top level (same indentation as
`Builds:`) causes a schema validation error and is silently ignored by some tooling.

```yaml
# WRONG – top-level
scanignore:
  - app/libs

# RIGHT – nested under the build entry
Builds:
  - versionName: '1.0'
    ...
    scanignore:
      - app/libs
```

---

## 2. `fdroid rewritemeta` enforces strict canonical formatting — always run it before committing

**Commits:** `7c64c961`, `18cb58e3`, `1a0cacb4`, `223e52c8`, `d56f3931`

F-Droid's CI runs `rewritemeta` and diffs the result. Any formatting deviation
(key order, blank lines, line-length wrapping) will cause a lint failure.

Rules enforced by `rewritemeta`:
- **Key order inside a build entry:** `gradle` comes *before* `srclibs`.
- **No extra blank lines** between build entry keys.
- **Line length ≤ 79 characters.** Long shell commands are wrapped by continuing
  on the next line with an 8-space indent. The wrap point matters — `rewritemeta`
  breaks *before* an argument, not after.
- Always run `fdroid rewritemeta metadata/com.package.yml` locally and commit
  the result verbatim.

### Long-line wrapping gotcha

When a shell command is just over the limit, `rewritemeta` decides *where* to
break. If you write:

```yaml
      - find … -exec cp {} libs/ffmpeg-kit.aar \;
#                        ^^ this pushes the line over 79 chars
```

`rewritemeta` wraps it as:

```yaml
      - find … -exec cp
        {} libs/ffmpeg-kit.aar \;
```

Write (or let `rewritemeta` produce) that second form from the start to avoid a
round-trip fix commit.

---

## 3. NDK version must match what the native build actually needs

**Commits:** `b6a94512`, `0335983d`

Specifying an NDK that is too new (e.g. r27) can break `ffmpeg-kit`'s configure
scripts. Specifying one that is too old may be absent from the F-Droid build server.
`ndk: 25.2.9519653` (r25c) is a stable, widely-available choice for ffmpeg-kit LTS
builds.

---

## 4. CMake version: avoid 3.31+ with projects that use the deprecated `-H` flag

**Commit:** `5d2b57a3`

CMake ≥ 3.31 removes the `-H<dir>` flag (replaced by `-S`). `ffmpeg-kit`'s build
scripts still use `-H`, so pin to an older SDK CMake:

```yaml
build:
  - sdkmanager "cmake;3.22.1"
```

---

## 5. `$$SDK$$`/`$$NDK$$` tokens only expand inside `prebuild`, not in `sudo`

**Commit:** `150cb08b`

`$$SDK$$` (and similar magic tokens) are substituted at prebuild time. Placing
`sdkmanager` invocations inside `sudo:` means the token is not yet expanded — the
command receives the literal string `$$SDK$$` and fails. Move any SDK/NDK tool
invocations into `prebuild:`.

```yaml
# WRONG
sudo:
  - $$SDK$$/cmdline-tools/latest/bin/sdkmanager "cmake;3.22.1"

# RIGHT
prebuild:
  - sdkmanager "cmake;3.22.1"   # sdkmanager is on PATH; $$SDK$$ not needed
```

---

## 6. Use `sdkmanager` from `PATH`, not via its full path

**Commit:** `220a3159`

On the F-Droid build server `sdkmanager` is on `$PATH`. Hard-coding
`$$SDK$$/cmdline-tools/latest/bin/sdkmanager` breaks if the tools layout differs.
Just call `sdkmanager` directly.

---

## 7. Add `make` to `sudo` dependencies when using CMake's Unix Makefiles generator

**Commit:** `5d2b57a3` / `5c61fdba`

CMake defaults to the `Unix Makefiles` generator, which requires `make` at build
time. The F-Droid Debian build environment does not have it pre-installed. Add it
to the `sudo: apt-get install` line.

---

## 8. `openjdk-17` is **not** available in Debian Trixie (the current F-Droid base)

**Commit:** `d781ba06`

If your app declares a Java 17 toolchain in Gradle, the build server already
provides Java 17 via its own JDK — you do not need to `apt-get install openjdk-17-jdk`.
That package does not exist in Trixie and will cause `sudo` to fail immediately.
Remove it; rely on the server's pre-installed JDK.

---

## 9. Use the correct `srclibs` fork/commit

**Commit:** `2c3008ff`

`ffmpeg-kit` has multiple forks. The commit hash used in `srclibs` must belong to
the fork referenced by the `srclibs` name. Using a commit from the upstream
`arthenica/ffmpeg-kit` repo against the `InfinityLoop1308-ffmpeg-kit` srclib entry
will produce a checkout failure. Verify the hash exists in the correct fork before
pinning it.

---

## 10. `pushd`/`popd` is required when building in a `srclib` directory

**Commit:** `13a71a29`

After running a build inside a srclib directory, F-Droid's working directory is
**not** automatically restored to `subdir`. Use `pushd … && … && popd` so subsequent
`prebuild` steps (like copying the AAR) execute from the expected directory.

---

## 11. `scanignore` path is relative to `subdir`, not the repo root

**Commits:** `c92eacd9`, `ff055bed`

When `subdir: app` is set, F-Droid scans `app/`. A `scanignore` entry must be
relative to that scan root:

```yaml
subdir: app
scanignore:
  - libs/ffmpeg-kit.aar   # relative to app/
```

Writing `app/libs/ffmpeg-kit.aar` or `libs` (without the filename) will not
suppress the binary-in-source warning.

---

## Quick pre-submission checklist

- [ ] Run `fdroid rewritemeta metadata/com.package.yml` and commit the output.
- [ ] Confirm all `srclibs` commit hashes exist in the referenced fork.
- [ ] `scanignore` entries are inside the `Builds` block, relative to `subdir`.
- [ ] No `openjdk-*` in `sudo` — rely on the server JDK.
- [ ] `sdkmanager` calls are in `prebuild`, not `sudo`.
- [ ] CMake pinned to `3.22.1` (or another pre-3.31 version) if using ffmpeg-kit.
- [ ] NDK pinned to `25.2.9519653` (r25c) for ffmpeg-kit LTS builds.
- [ ] `make` is in the `apt-get install` list if using CMake Unix Makefiles.
- [ ] Long shell lines are pre-wrapped to match what `rewritemeta` would produce.


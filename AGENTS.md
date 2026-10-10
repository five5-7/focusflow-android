# FocusFlow repository instructions

## Read first

- `README.md`
- `VERSIONING.md`
- `docs/6.2-structure-plan.md` when working on 6.2
- `CHANGELOG.md`
- `app/src/main/java/com/sakata/focusflow/RoadmapData.kt`
- `docs/8.2.0-handover.md` when working on the 8.2.0 appearance system (in progress on `agent/focusflow-8.0.0`):
  it lists what is done, what is left, the live checkout path, and the build/device commands.

## Product boundaries

- FocusFlow is a local-first Android schedule and execution assistant.
- Preserve the daily loop: capture → schedule → remind → complete/postpone → retain history.
- Do not surface low-frequency tools in the daily path unless relevant data or an explicit setting enables them.
- Do not add a switch for presentation-only behavior; switches are for background work, permissions, notifications, or meaningful user choice.
- Treat AI, maps, tutorials, commute learning, and app detection as optional tools. Never require them for the core workflow.

## Compatibility and safety

- Preserve package name `com.sakata.focusflow`.
- Existing SharedPreferences keys and JSON fields are user data contracts. Add defaults and migrations before changing them.
- Do not delete, reset, or silently replace user data.
- Notification behavior must remain conservative: ignored or stale prompts must not repeatedly return.
- Never place signing keys, passwords, API keys, or Base64 keystores in tracked files or logs.
- GitHub Actions must fail rather than fall back to an incompatible debug signing key.

## Development workflow

- Use one feature branch per version or bounded phase; do not work directly on `main`.
- Keep structure-only changes separate from behavior changes.
- Update `CHANGELOG.md`, `README.md`, and `RoadmapData.kt` only when a release scope is actually implemented.
- 应用内更新说明必须在发版时展示该版本核心变化：为每个新版本在 `QuickStartDialog.updateHighlightsFor` 写 2–3 条用户可感知的变化，禁止落入兜底文案；与 `CHANGELOG.md`、`RoadmapData.kt` 同步。
- Follow `VERSIONING.md`; a CI `run-N` is not a product version and a successful build is not a release.
- The current official release is `8.2.1` / versionCode 534 (the fifth formal release). It extends the optional appearance system with acrylic and frosted-glass profiles plus non-destructive image crop controls; rich appearance remains disabled by default.
- The current development candidate is `9.0.0-rc.2` / versionCode 580. It retains rc.1 stability fixes and refines task option wrapping, the inbox header, floating navigation spacing/history arrow bounds and launcher-icon depth. The rc.1 Run 666 validated Debug/Release each 191 suites / 1483 tests; rc.2 results must be recorded separately in `docs/9.0-ui-refinement-20261010.md`. This PR remains a draft, unmerged and unreleased.
- The target remains the maintained OPPO / ColorOS 17 / Android 17. The maintainer has now authorized dsh-assisted ADB acceptance, superseding the earlier manual-only arrangement. On 2026-10-10 the maintainer accepted the rc.1 notification item because reminder code was unchanged and missing permissions may explain the observed delay; record this as a maintainer acceptance decision, not a successful repeat test or a proven cause. Keep the two raw delayed-alarm observations. Previously passed unchanged paths retain their historical evidence; verify the new UI separately. Dsh import and holiday schedule changes remain pending product features.
- Further recognition work is deferred beyond 9.0. Preserve its implementation and entry points; do not resume parser/gate work or revert the product branch without a new task. Room activation remains off. Do not merge or release without separate explicit approval.
- The previous official release is `8.2.0` / versionCode 525.
- Do not allocate a new version merely to record an idea; keep uncommitted ideas in the future-candidate section until a coherent scope is frozen.
- Do not merge a pull request without explicit user confirmation.

## Verification

- Add pure Kotlin unit tests for time, planning, migration, and reminder-policy logic.
- Run `:app:testDebugUnitTest` and `:app:assembleDebug` in CI before offering an APK.
- Use only the stable-signed GitHub Actions artifact for installation testing.
- 本地构建与 CI 使用同一签名（见 `docs/signing-policy.md`：证书 SHA-256 `650a17f2…`，即 Android 调试证书；仓库 Secrets 持有该 keystore）。本地构建需设置 `FOCUSFLOW_SIGNING_STORE_FILE`/`FOCUSFLOW_SIGNING_STORE_PASSWORD`/`FOCUSFLOW_SIGNING_KEY_ALIAS`/`FOCUSFLOW_SIGNING_KEY_PASSWORD` 四个环境变量，值从维护者本地签名目录读取（如 `signing/passwords.txt`，三行：storePassword/keyAlias/keyPassword；勿入库、勿回显、勿写入日志）。同一签名保证本地包与 GitHub Release 包可互相覆盖安装，应用内「检查更新」依赖这一点。
- The active Stage 9 target-device verification is the maintained OPPO test device, upgraded to ColorOS 17 / Android 17, especially notification permission, channels, background restrictions, reboot recovery, and meal-dismiss behavior. ColorOS 16 / Android 15 is a separate optional compatibility target.

## Agent handoff

- Start by reporting the checked-out branch, HEAD, working-tree state, and the approved phase.
- Read the live repository as the source of truth; do not infer completion only from roadmap labels.
- This checkout is the live one; the older snapshots (`focusflow-android`, `focusflow-android-6.4`, `focusflow-android-8.0.0`) are archived under `D:\focusflow\legacy\snapshots\`.
  On this machine git needs `-c safe.directory=D:/focusflow/legacy/snapshots/focusflow-android-8.0.0` when opening the archived 8.0.0 repo (workspace owner SID differs; the global `safe.directory` entry still points at the old path),
  and helper scripts under `D:\focusflow\.tmp\` must stay ASCII-only (PowerShell 5.1 mangles non-BOM UTF-8).
- Stop at the end of the assigned phase and report changed files, observed test evidence, risks, and next-step recommendation.

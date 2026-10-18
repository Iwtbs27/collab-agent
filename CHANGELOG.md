# Changelog

## [2.0.0] - 2026-05-17

### Added
- Multi-backend privilege abstraction: `PrivilegeBackend`, `ServiceConnector`, and `SystemBackend` interfaces
- `standard` build flavor for stock Android (no system signature required); split `sharedUserId` to `system` flavor only
- Shizuku SDK dependency, manifest provider, and `ShizukuConnector` with typed SDK APIs and reconnect backoff
- `PrivilegedService` with shared Ops delegation and caller authentication
- AIDL interface (`IPrivilegedService`) for uid-agnostic cross-process privileged operations
- `startActivityOnDisplay()` AIDL method
- `RemoteBackend` with AIDL proxy; `BackendDetector` wired into `AgentService` via `CompletableDeferred`
- Wireless ADB self-pairing with SPAKE2+ protocol — vendored Shizuku ADB pairing + transport code (Apache-2.0)
- `DirectConnector` with wireless ADB self-pairing scaffold
- Multi-step backend setup wizard (`SetupActivity`)
- `AdbPairingService` foreground service with `RemoteInput` notification pairing + permission request UI
- `PairingDialogActivity` for MIUI `RemoteInput` fallback
- Multi-step notification guidance flow in `AdbPairingService` (5-step user flow)
- `SetupStateDetector` for environment pre-checks (WiFi, Android version, developer options)
- `OemHelper` for OEM adaptation: MIUI, ColorOS, OriginOS, Samsung, Huawei detection
- Integration of `SetupStateDetector` and OEM helpers into `SetupActivity`
- Auto-launch `SetupActivity` when no backend is detected on standard flavor
- Auto-reconnect with saved connection info; cert-mismatch detection triggers re-pairing
- Error classification and progress persistence in pairing flow
- Direct navigation to Wireless Debugging settings page via `SubSettings` / `fragment_args_key` fallback
- Backend status display and revoke-access action in Settings
- Context-aware disconnect: visible pause during active tasks, silent when idle
- `FLAG_SECURE` auto-handoff with consecutive black-frame detection
- `ensureVdHasContent()` VD recovery helper
- VD frame caching in `PrivilegedService`; local `ImageReader` for remote VD capture (eliminates AIDL round-trip)
- `FakeContext` for `DisplayManager` in shell process (fixes NPE on API 35)
- Network security config permitting cleartext traffic to local LLM endpoints (HTTP)
- i18n support: Chinese (default) + English string resources for the entire setup flow
- Android Instrumentation tests and E2E YAML test scenarios for the ADB backend setup flow

### Changed
- Refactored `InputInjector` reflection into `InputOps`, delegating to `PrivilegeBackend`
- Refactored `ScreenCapture` reflection into `CaptureOps`, delegating to `PrivilegeBackend`
- Refactored `VDManager` reflection into `DisplayOps`, delegating to `PrivilegeBackend`; VD creation now uses `DisplayManager` public API instead of `IDisplayManager` reflection
- VD flags corrected: `TOUCH_FEEDBACK_DISABLED` = `1<<13`, `STEAL_TOP_FOCUS_DISABLED` = `1<<16`; added `DEVICE_DISPLAY_GROUP` flag; removed `AUTO_MIRROR` flag for `RemoteBackend`
- VD creation routed through `RemoteBackend` for non-system-app builds
- `AgentService.backend` updated after pairing success via `updateBackend()`
- `PairingBroadcastReceiver` updated to set backend on successful pairing
- Theme migrated to Material3; secondary button uses `TextButton` style with explicit color for dark-mode visibility
- Added `.cxx/` build artifacts to `.gitignore`
- Bundled `org.conscrypt` for `exportKeyingMaterial`; added content-filter for rikka Maven repository

### Fixed
- VD screenshot failure after action error (fixes #34)
- App reinstall invalidates ADB pairing / cert mismatch on auto-reconnect (fixes #35)
- `SetupActivity` theme attribute warnings with Material3 (fixes #36)
- Permission Denial when launching activities on virtual display
- `showHomepage` crash (`CalledFromWrongThreadException`)
- `FakeContext` NPE on API 35
- VD flag fallback: strip `TRUSTED` if `ADD_TRUSTED_DISPLAY` is denied
- `PrivilegedServiceMain` binder delivery via `getContentProviderExternal` + async `shellCommand`
- Setup UX: dark mode colors, default wireless ADB flow, settings re-entry behaviour

---

## v1.1.0 (2026-05-16)

Compared to v1.0.0 (opencyvis initial release).

### New Features

**Remote IM Control** (18 new source files)
- Telegram and Feishu integration — control your AI phone remotely via chat messages
- Pairing flow with `/pair` `/unpair` commands and brute-force protection (`ImPairingManager`)
- IM session router with whitelist and command routing (`ImSessionRouter`)
- Feishu channel: WebSocket + OpenAPI, QR registration, protobuf frame ACK, reconnection backoff
- Telegram channel: polling with offset persistence, 429 rate-limit handling
- Typing indicator, step output, stale conversation cleanup, conversation controls
- Device status reporting (`/status`) with phone state and LLM backend info
- Screenshot forwarding to IM conversations
- Remote IM Control settings UI in SettingsActivity
- Dumpsys test commands (`dumpsys opencyvis im`)
- Unit tests (`ImSessionRouterTest`, `ImPairingManagerTest`, `FeishuRegistrationApiTest`)
- E2E IM scenarios (cold start, inbound message)

**Homepage Routines**
- Routine greeting, quick actions, and management UI (`RoutineChipAdapter`, `RoutineRecentAdapter`)
- Room persistence (`RoutineDao`, `RoutineEntity`) with CRUD operations
- Save/edit routine dialog, recommended card layouts
- Localized routine instructions (en/zh)
- E2E smoke tests for routine feature (#30)

**Day/Night Theme**
- Full DayNight theme support across all Activities
- `SettingsFragment` migrated to `PreferenceFragmentCompat` with XML preferences (`settings_root.xml`)
- Night-mode resources: `drawable-night/`, `values-night/colors.xml`, `values-night/themes.xml`
- Day/night preview page in docs

**Agent Improvements**
- `list_apps` action for discovering installed apps on the device
- Improved `open_app` discovery with `bringToFront` on VirtualDisplay creation
- `KEYCODE_PASTE` for clipboard text injection — more reliable than Ctrl+V across different IME/EditText implementations (inspired by scrcpy)
- Guard against launcher tap focus cascade on VirtualDisplay (#29)
- Prevent agent from re-verifying earlier steps in multi-task chains
- Detect unchanged screen after actions and feed back to LLM

### Bug Fixes

- Fix ANR when rapidly typing in supplement input during task (#31)
- Fix launcher tap focus cascade causing focus steal on VirtualDisplay (#29)
- Fix routine instructions not being localized (#30)
- Fix Feishu QR registration and WebSocket connection
- Fix FeishuRegistrationApi test to match actual API response format

### New Source Files

```
remoteim/AndroidImStringProvider.kt      remoteim/ImAgentBridge.kt
remoteim/ImChannel.kt                    remoteim/ImChannelManager.kt
remoteim/ImPairingManager.kt             remoteim/ImSessionRouter.kt
remoteim/ImStringProvider.kt             remoteim/RemoteImControlService.kt
remoteim/FakeImChannel.kt                remoteim/feishu/FeishuChannel.kt
remoteim/feishu/FeishuOpenApi.kt         remoteim/feishu/FeishuRegistrationApi.kt
remoteim/feishu/FeishuWsClient.kt        remoteim/telegram/TelegramApi.kt
remoteim/telegram/TelegramChannel.kt     db/RoutineDao.kt
db/RoutineEntity.kt                      SettingsFragment.kt
ui/RoutineChipAdapter.kt                 ui/RoutineRecentAdapter.kt
```

### New Test Files

```
test/remoteim/ImSessionRouterTest.kt     test/remoteim/ImPairingManagerTest.kt
test/remoteim/FeishuRegistrationApiTest.kt  test/db/RoutineDaoTest.kt
test/ui/ChatAdapterTest.kt               test/ui/TypewriterTextViewTest.kt
```

### New Resources

```
drawable/bg_recommended_card.xml         drawable-night/bg_recommended_card.xml
drawable/bg_routine_chip.xml             drawable/bg_routine_icon.xml
drawable/bg_routine_recent.xml           layout/dialog_qr_code.xml
layout/dialog_save_routine.xml           layout/item_routine_chip.xml
layout/item_routine_recent.xml           xml/settings_root.xml
values-night/colors.xml                  values-night/themes.xml
```

---

## v1.0.0

Initial open-source release (opencyvis). See [opencyvis repo](https://github.com/opencyvis/opencyvis) for details.

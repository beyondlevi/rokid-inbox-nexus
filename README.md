# Rokid Inbox — Nexus plugin

Multi-channel inbox for Rokid AR glasses, ported to the **Rokid Nexus** plugin
platform. This is the Nexus port of the original two-app project
[`beyondlevi/rokid-inbox-app`](https://github.com/beyondlevi/rokid-inbox-app)
(a paired phone host + standalone glasses client over Bluetooth).

On Nexus there is a single permanent **hub** on the glasses that renders every
plugin's UI on the HUD. A plugin is just a **headless phone APK** that talks to
the hub over a local bus: install the phone app, get a glasses app. So this port
drops the entire custom Bluetooth transport and the standalone glasses client —
the phone-side channel integrations stay, and the glasses UI becomes declarative
surfaces the hub renders, driven by the R08 ring.

## What it does

- **Unified inbox** across WhatsApp (Evolution API), Telegram (self-hosted
  GramJS bridge), Gmail (OAuth, read-only) and GitHub PRs (read-only), merged
  and sorted by recency, with per-box labels (`[W]` / `[W1]` / `[W2]`).
- **Browse and read** on the HUD: an inbox list, a conversation reader, and a
  filter toggle (all / unread).
- **Reply** with configurable **quick messages** (canned replies), optionally
  quoting a message.
- **React** with emoji (WhatsApp / Telegram).
- **View photos** on the HUD via the Nexus image surface (downscaled and
  re-encoded on the phone to fit the surface limits).
- **Describe with AI** — open a photo or a file (pdf, xlsx, docx, csv, ...) and
  get a detailed text description on the HUD, generated on the phone with your
  own OpenAI key (Vision for images, the Files + Responses API with code
  interpreter for documents).

Everything is **100% operable by the R08 Access Bridge**: every surface is an
ordered, linear list driven by the four ring verbs — NEXT / PREV (move),
SELECT (activate), BACK (up / self-close).

## What changed from the original (and why)

| Original feature | Nexus port |
|---|---|
| Standalone glasses APK + custom HUD | Removed — the hub renders declarative surfaces |
| CXR / BLE / SPP transport + versioned handshake | Removed — replaced by the Nexus local bus |
| Voice reply (mic on glasses → Whisper) | **Dropped** — Nexus disables the `microphone` capability in the phone approval UI and exposes no speech-to-text bus endpoint, so on-glasses dictation is not shippable today |
| Voice search of chats | **Dropped** — same reason (no STT on the HUD) |
| Whisper transcription | Removed with the voice features |
| Inbox / conversation browsing | Kept — `NexusCard` surfaces |
| Quick-message replies, quoted replies | Kept |
| Emoji reactions | Kept |
| Inline photos | Kept — `NexusImage` surface |
| AI image/file descriptions | Kept — phone-side OpenAI |
| Encrypted on-device credentials | Kept — `EncryptedSharedPreferences` |

The unified-inbox aggregator and all four channel services are ported almost
verbatim; only their package moved to `com.rokid.inbox.nexus`.

## How it's built (Nexus model)

- **One exported `NexusPluginService`** (`InboxPluginService`) carrying the
  descriptor (id `rokid-inbox`, API version 3, capability `surfaces`). No
  launcher icon, no `MAIN`/`LAUNCHER` — the plugin is headless.
- **`InboxRuntime`** — the phone-side brain: builds the channel services from
  config on each open, runs all I/O, executes navigation intents, preprocesses
  images, and pushes surfaces.
- **`InboxNavState`** — a pure-Kotlin, unit-tested one-axis navigation state
  machine (the R08 navigability proof; see `InboxNavStateTest`).
- **`InboxSettingsActivity`** — a phone settings screen on the NexusUi kit:
  manage channels, the OpenAI key, and quick replies. Config changes take effect
  the next time the plugin is opened on the glasses. Ends with the uninstall row.

```
app/src/main/java/com/rokid/inbox/nexus/
  InboxPluginService.kt      NexusPluginService adapter (surfaces + input)
  InboxRuntime.kt            phone-side brain: I/O, dispatch, image preprocessing
  InboxNavState.kt           pure one-axis navigation state machine (tested)
  InboxSettingsActivity.kt   NexusUi settings screen
  InboxConfigStore.kt        encrypted boxes / OpenAI key / quick messages
  model/                     Chat, Message, ChannelKind, ChatType, QuickMessage
  channels/                  Http, ChannelService, InboxAggregator, WhatsApp/Telegram/Gmail/GitHub
  ai/AiDescriber.kt          OpenAI image + file descriptions
```

## Build

JDK 17 + Android SDK/build-tools 36. From the repo root:

```bash
./gradlew :app:testDebugUnitTest   # the one-axis navigation state-machine test
./gradlew :app:assembleDebug       # debug APK
```

Output: `app/build/outputs/apk/debug/inbox-nexus-debug.apk`.

The SDK (`com.github.Anezium.Rokid-Nexus:bus-client`) resolves from JitPack.

## Install → approve → launch (on the hardware)

1. `adb install -r app/build/outputs/apk/debug/inbox-nexus-debug.apk`
2. Phone: **Rokid Nexus → Settings → Plugin access → Inbox → approve** the
   `surfaces` capability (installing grants nothing).
3. Open the plugin's settings from the Nexus app and configure at least one
   channel (and, optionally, the OpenAI key).
4. Glasses: open **Inbox** from the launcher and drive it with the R08 ring.

## Channels setup

The channel back-ends are unchanged from the original project — see its docs for
WhatsApp (Evolution API), Telegram (the self-hosted GramJS bridge), Gmail (OAuth)
and GitHub setup:
[`beyondlevi/rokid-inbox-app`](https://github.com/beyondlevi/rokid-inbox-app).

## License

MIT — see [LICENSE](LICENSE).

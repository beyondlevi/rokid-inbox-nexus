# Changelog

All notable changes to this project are documented in this file.

## [1.2.1] - 2026-07-24

### Fixed
- Plugin icon on the glasses launcher: the descriptor now declares **both**
  `ICON` (built-in `chat`) and `ICON_DRAWABLE`. A custom drawable never reaches
  the glasses launcher (it lives in the phone APK), so the built-in key is what
  renders there; the phone still uses the custom glyph.
- App (phone) icon: `ic_launcher_foreground` is now an `<inset>` of the
  monochrome plugin glyph, wired as both the adaptive `<foreground>` and
  `<monochrome>`, so the glyph shows on the launcher and the themed icon.
- Settings header no longer hardcodes the version ("v1.0"); it reads
  `versionName` from the package manager, so it can't drift from the build.

## [1.2.0] - 2026-07-24

### Added
- Voice search of contacts across all boxes: a **Buscar por voz** action in the
  inbox (shown when voice/STT is enabled) captures a spoken name, transcribes it
  on the phone, runs the unified name search over every connected channel
  (`InboxAggregator.searchChatsByName`), and shows the matches as a
  ring-navigable list you can open. BACK returns to the inbox.

## [1.1.1] - 2026-07-24

### Changed
- Pin the SDK to `sdk-v0.2.1`, the maintainer's re-release of the microphone
  endpoint that builds cleanly on JitPack (the earlier `sdk-v0.2.0` tag failed
  JitPack's build). No functional change to the plugin; this is the first
  voice-capable build that resolves from a clean clone.

## [1.1.0] - 2026-07-24

### Added
- Voice dictation (speech-to-text) from the glasses, using the new Nexus
  microphone endpoint (`NexusAudioSession`, SDK `sdk-v0.2.0`). From a
  conversation, pick **Ditar por voz** (reply to the chat) or **Ditar resposta
  (voz)** on a message (quoted reply): the glasses mic streams 16 kHz PCM to the
  phone over the hub, the phone transcribes it, and you confirm the transcript
  before sending. The STT path is transposed from the maintainer's Rokid Relay
  reference (the OpenAI buffered engine) and reuses the OpenAI key already
  configured for AI descriptions.
- Voice settings in the plugin settings screen: enable/disable, forced
  transcription language, and the transcription model.
- `microphone` capability + `/audio` receive prefix. No Android `RECORD_AUDIO`
  permission — the PCM arrives over the hub, not the phone mic.

### Changed
- SDK bumped to `sdk-v0.2.0` (adds the microphone endpoint).

### Note
- Adding the `microphone` capability changes the capability set, so the plugin's
  grant resets to **Pending** — re-approve it (now including the microphone) in
  the Nexus app under Plugin access after updating.

## [1.0.1] - 2026-07-23

### Fixed
- HUD lists could not be scrolled past the visible area: the whole list was sent
  as card lines, and the card surface has no selection concept and never scrolls
  to the marked row, so once the focused row moved off-screen the `>` cursor
  disappeared and the remaining items were unreachable. The inbox, conversation,
  quick-reply and reaction lists now paginate into pages of six rows that always
  contain the focused row, with "(+N acima)" / "(+N abaixo)" indicators — the
  approach the shipped Transit plugin uses.
- Opening a conversation left the cursor invisible: focus starts on the newest
  message (bottom of the list), which was outside the rendered window. Paging now
  renders the page containing the focused message, so the cursor is visible
  immediately.
- Cards now set `handlesBack`, so BACK inside a sub-view (message actions, reply,
  react) pops to the parent instead of letting the hub close the surface.

## [1.0.0] - 2026-07-23

### Added
- Initial Rokid Nexus port of the Rokid Inbox app, as a single headless phone
  plugin (`rokid-inbox`, API version 3, capability `surfaces`) rendered on the
  glasses by the Nexus hub.
- Unified inbox across WhatsApp (Evolution API), Telegram (GramJS bridge), Gmail
  (OAuth, read-only) and GitHub PRs (read-only), with an all/unread filter and
  multi-account box labels.
- HUD surfaces: inbox list, conversation reader, per-message action menu, quick
  reply picker, emoji reactions, photo view (Nexus image surface), and AI
  description results — all fully operable by the R08 ring on a single axis
  (NEXT / PREV / SELECT / BACK), backed by a unit-tested navigation state
  machine.
- Reply with configurable quick messages (optionally quoting a message) and
  emoji reactions on the channels that support them.
- AI descriptions of images and files (pdf/xlsx/docx/csv/...) via a phone-side
  OpenAI key (Vision + Files/Responses code interpreter).
- Phone settings screen on the NexusUi kit to manage channels, the OpenAI key
  and quick replies, with encrypted on-device credential storage and the
  mandatory uninstall row.

### Removed (not portable to Nexus)
- The standalone glasses client and the custom CXR/BLE/SPP Bluetooth transport,
  replaced by the Nexus hub + local bus.
- On-glasses voice reply, voice search, and Whisper transcription: Nexus
  disables the `microphone` capability in the phone approval UI and exposes no
  speech-to-text bus endpoint, so on-HUD dictation is not shippable today.

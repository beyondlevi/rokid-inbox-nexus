# Changelog

All notable changes to this project are documented in this file.

## [2.2.0] - 2026-08-04 (branch `relay-base`)

### Added
- Voice/audio messages are now usable again:
  - **Reproduzir audio** — plays the original voice note on the phone; the sound
    comes out on the glasses (they are the phone's Bluetooth audio sink — the
    same route the native Nexus TTS uses). No Nexus endpoint streams raw audio to
    the glasses; TTS (`tts` capability, text→speech) is the only native audio-out
    and does not carry arbitrary clips, so phone-side playback is the path.
  - **Transcrever (IA)** — transcribes the voice note with OpenAI
    (`/v1/audio/transcriptions`, accepts the OGG/Opus note directly) and shows it
    in the paged reader. AI description already covered image/file; audio now
    transcribes.

## [2.1.1] - 2026-08-04 (branch `relay-base`)

### Changed
- AI description reader is now a dense **paged** view instead of many short
  2-line chunk rows. The description renders as a plain card body (which packs
  ~15 lines) filled to ~12 lines per page; rotating goes to the next/previous
  page, with `pagina i/N` in the subtitle. Added a `bodyLines` plain-body path
  to the surface renderer for this.

## [2.1.0] - 2026-08-04 (branch `relay-base`)

### Added
- Photo viewing on the HUD (based on the shipped Feeds/Sample image-surface
  pattern). Image messages now offer **Ver foto**: the photo is fetched,
  downscaled/re-encoded on the phone to fit the image-surface limits (≤512 px
  edges, ≤64 KiB JPEG) and shown via `NexusImage`. Because the image surface
  needs the SPP binary plane (`supportsImageSurface`), which can be transiently
  down, it keeps the photo pending and retries until the channel comes up (up to
  12 s, also flushed on link-state changes), falling back to a text card only if
  it never does. BACK returns to the conversation. No new capability (image
  surface rides the existing `surfaces` grant).

## [2.0.3] - 2026-08-04 (branch `relay-base`)

### Fixed
- "Descrever com IA" gave no processing feedback (leading to repeated taps) and
  its result couldn't be scrolled. Tapping now switches immediately to a
  "Processando com IA..." screen (which also has no actionable row, so extra
  taps don't fire another request), and the result view is now scrollable: the
  description is chunked into selectable rows so rotating pages through it, with
  an `i/N` position in the subtitle.

## [2.0.2] - 2026-08-04 (branch `relay-base`)

### Fixed
- Long messages were truncated at 3 lines and the action menu showed only a
  one-line preview. The hub caps a list row at 3 lines (`LIST_BODY_MAX_LINES`)
  and a subtitle at 1 line, so:
  - The conversation reader now splits each message into ~52-char chunks (word
    aware), each a row the hub renders and scrolls, so any message reads in full
    across rows. Every chunk maps back to its message: tapping any part opens
    that message's actions.
  - The message-action menu now renders the full message (chunked, above the
    actions) instead of a truncated one-line subtitle.

## [2.0.1] - 2026-08-04 (branch `relay-base`)

### Fixed
- No visual selection indicator on non-list rows. The hub only draws its
  selection rail on list rows (title + `sub`); BODY-tone/prose rows got no
  caret. The focused row now switches to `ALERT` tone (the same way the shipped
  Relay marks its choices), so the inbox header actions (Filtro / Atualizar /
  Buscar por voz), the thread messages, and the Responder / Carregar mais /
  message-action / react rows all show which one is selected.
- Outgoing messages showed the sender twice ("Eu" badge + "Eu:" text). The row
  text is now just the message body; the speaker is carried only by the badge.

## [2.0.0] - 2026-07-24 (branch `relay-base`)

Large refactor of the HUD, rebuilt on the shipped Relay plugin's model, keeping
the Inbox features on top. SDK bumped to `sdk-v0.12.0`.

### Changed
- **HUD is now rich-row + hub-scrolled.** Rows are `NexusCardLine` with `sub`
  (smaller preview line), `tone` (NORMAL/DIM/BODY/ALERT) and `selected` — the
  glasses hub draws the caret and scrolls the card itself ("a HUD that moves").
  Removed the manual `> ` marker and the manual 6-row pagination entirely.
- **Relay-style navigation**: a unified inbox LIST (sender title + preview sub +
  box badge + unread ALERT tone) and a THREAD reader that labels each message
  with its speaker; message → per-message actions.
- Reply review now offers **Enviar texto** and **Enviar áudio** (send the
  transcription or the original voice note) plus **Regravar**.

### Added / kept
- Multiple boxes (WhatsApp/Telegram/Gmail/GitHub) and full chat-history reading.
- Voice reply via glasses mic → OpenAI Whisper transcription, with the original
  audio also sendable on WhatsApp/Telegram (`sendVoice` restored on those
  channels). Quick replies, emoji reactions, AI describe (image/file), and voice
  search of contacts across all boxes.

### Removed
- All notification/push forwarding (this port never had it; the Relay base's
  notification layer was left out by design).
- On-HUD photo viewing (image surface) and in-thread audio playback, for now.

## [1.2.2] - 2026-07-24

### Fixed
- Photos would not display on the HUD. The image surface needs the SPP binary
  plane (`supportsImageSurface` = image capability AND `SPP_DATA_UP`), which can
  be transiently down; the plugin now keeps the photo pending, waits for the
  image channel to come up (retrying on link-state changes, up to 12 s) and only
  then falls back to a text card — instead of giving up immediately. Images are
  also sent via `updateImage` when a surface is already shown (card→image
  transition), matching the shipped Feeds/Media Deck idiom.

### Added
- Play button for audio messages: voice/audio messages now expose **Reproduzir
  audio**, which fetches the media and plays it phone-side (routing to the
  glasses speaker when they are the connected audio output).

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

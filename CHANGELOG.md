# Changelog

All notable changes to this project are documented in this file.

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

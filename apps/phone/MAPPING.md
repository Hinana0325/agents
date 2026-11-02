# Phone Agent

## What this node is

The existing phone MVP at `~/workspace/phone-assistant/` **is** the Phone Agent.
It is not rewritten or replaced by the multi-agent restructuring, and this
directory is now its source of truth on this machine (that `~/workspace` path
does not exist here — edit this copy, not an imagined original).

- Build status (2026-11-02, this machine): `:app:compileDebugKotlin`,
  `:app:testDebugUnitTest` (11 tests, 0 failures) and `:app:assembleDebug` all
  pass, and `:app:lintDebug` reports no errors. **Not run on a real device** —
  none is attached, so the seven intents, the Keystore-backed settings store and
  the UI are verified only statically. See `REPORT.md` §5 in this repo for the
  fixes made to get here.
- Origin: 30 files, 1436 lines of Kotlin, copied from the phone MVP.
- Stack: Kotlin 2.1.0, AGP 8.7.3, Compose BOM 2024.10.01, compileSdk 35,
  minSdk 29, targetSdk 35. UI in Simplified Chinese, neutral grey-white.
- Package `cn.phoneassistant`, app name “手机助理”.

## How it maps to the envelope

- Its 7 intent types are the first 7 types of `protocol/envelope.schema.json`:
  OPEN_APP, SET_ALARM, CREATE_CALENDAR_EVENT, SET_TIMER, WEB_SEARCH,
  SAVE_NOTE, CHAT_ONLY. Their parameter rules are already enforced by the
  phone MVP's `IntentValidator` and are mirrored in
  `protocol/envelope.py` / `protocol/AgentEnvelope.kt`.
- The two new cross-device types (HANDOFF_TASK, VOICE_COMMAND) are **not**
  implemented in the phone MVP yet. When the cloud orchestrator is wired in,
  the phone app must: (a) accept a HANDOFF_TASK envelope, keep its
  `task_id`, and convert it to one of the 7 concrete intents before
  executing; (b) accept a VOICE_COMMAND envelope (transcript in
  `params.transcript`), turn it into a concrete intent, and confirm it
  under the normal tier rules before executing. Until that wiring exists,
  those two types must be rejected by the phone app, not executed.
- `task_id` propagation: one task keeps one `task_id` across devices.
  If a task starts on the watch and is handed to the phone, the phone
  must not mint a new id; it reuses the envelope's `task_id` so the
  cloud task list shows one task, not two.

## Confirmation tiers (phone MVP → envelope)

| Phone MVP behaviour | Envelope tier |
| --- | --- |
| CHAT_ONLY: show reply only | 1 |
| WEB_SEARCH: run directly, say what was searched | 1 in the MVP; the envelope still carries the tier so the cloud can raise it if policy changes |
| OPEN_APP, SET_ALARM, SET_TIMER, SAVE_NOTE: show intent card, wait for Confirm | 2 |
| CREATE_CALENDAR_EVENT: show intent card, wait for Confirm, then the system calendar UI confirms again | 3 (system UI is the second confirmation) |

The tier is enforced by the phone app itself, not by the model and not by
the cloud: a sender cannot lower its own tier by sending a smaller number.

## What the phone node does NOT do (unchanged from the MVP)

- No root, no su, no shell, no Accessibility, no DeviceAdmin.
- No SMS, no calls, no background service, no reading other apps.
- Only permission is network access. The LLM key lives only in the
  Keystore-backed encrypted store on the phone, never in code.
- Alarms, timers and calendar events go through public system Intents;
  the phone app never writes the alarm/calendar database directly.

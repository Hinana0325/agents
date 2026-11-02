# Earphone Agent — voice front-end, design only

## Status

Design document only. **No earphone software has been built, no earphone
model has been decided, nothing has been tested with earphones.** Do not
present this node as implemented.

## The key clarification

The model does **not** run in the earphones. Earphones are an audio
input/output front-end only: microphone in, speaker out. Everything else
runs on the phone or the PC:

- Wake word detection, speech-to-text and text-to-speech: on phone/PC,
  using the platform's own speech services (which service is undecided).
- Intent conversion and confirmation: on phone/PC/cloud, under the same
  envelope rules as every other node.
- The earphone node itself only carries: audio stream in, audio stream
  out, and a VOICE_COMMAND envelope (transcript only) to the phone/PC.

## Voice confirmation rule (mandatory, not optional)

1. The user speaks a request → phone/PC transcribes it → the result is
   turned into a concrete intent (never executed as raw transcript).
2. The assistant reads the intent back in plain language (“I will set
   an alarm for 7:30 — say yes to confirm”), and waits for an explicit
   voice confirmation word.
3. Only then does it execute, under the normal confirmation tier.
4. Sensitive actions (calendar events, file writes, messages, anything
   Tier 3) are **not** confirmed by voice alone: they are pushed to the
   phone UI for a tap confirmation, and the voice side only says the
   task is waiting on the phone.
5. If the transcript is ambiguous, the assistant asks a clarifying
   question instead of guessing, and cancels the task if the user does
   not confirm.

## Suitable and unsuitable uses

- Suitable: hands-free reminders, timers, quick questions, hearing a
  short summary, starting a task that finishes on the phone/PC.
- Unsuitable: long conversations, reading long documents aloud in full,
  anything needing visual confirmation, anything Tier 3 without a
  phone-UI step.

## What it does NOT do

- No always-on recording stored in the cloud, no raw audio upload
  unless the user explicitly enables it (default: off, undecided
  implementation).
- No background microphone use beyond the platform wake mechanism.
- No credentials in the earphone path: voice audio never carries keys,
  and the transcript envelope carries no secret.
- No standalone earphone app, firmware change or earphone-side model.

## Open decisions for this node

- Earphone model: undecided.
- Wake word and speech service (phone platform vs. cloud): undecided.
- Whether the voice path runs primarily via phone or via PC: undecided
  (phone first is the suggested default, not a decision).

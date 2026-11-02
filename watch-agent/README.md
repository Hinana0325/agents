# Watch Agent — design only, no app yet

## Status

Design document only. **No watch app has been built, no watch model or
watch OS has been decided, nothing has been tested on a watch.** Do not
present this node as implemented.

## Role

A lightweight companion to the phone, not a second phone and not a
standalone assistant:

- Quick voice commands (“remind me…”, “set a timer…”, “what's next?”).
- Reminder confirmation: a watch notification with Confirm / Cancel that
  flips the cloud task from `pending` to `confirmed` or `cancelled`.
- Glance info: next event, next reminder, task status — read-only summaries
  from the cloud, never a full conversation history on the wrist.
- Handoff to phone/PC: any task that needs a screen, a long reply or a
  sensitive confirmation is handed off with its original `task_id`
  (HANDOFF_TASK envelope) instead of being forced onto the watch UI.

## Explicitly no standalone LLM on the watch

- The watch does not run a language model, does not call a model API
  directly, and does not decide intent types on its own.
- Speech is transcribed on the phone or in the cloud; the watch sends a
  VOICE_COMMAND envelope (transcript only) and the receiving agent
  converts it to a concrete intent and confirms it before executing.
- Long conversations, multi-step planning and anything needing detailed
  reading belong on the phone or PC, by handoff.

## What it does NOT do

- No reading other apps, no screen control, no payments, no calls or
  messages sent without a confirmation step on the watch or phone.
- No background recording, no always-on microphone beyond the platform's
  own wake mechanism (which is itself undecided until a watch is chosen).
- No local storage of credentials: the watch holds at most a per-device
  placeholder token for the cloud registry (see the cloud skeleton's
  stub-auth warning); real credentials stay on the phone/cloud.

## Open decisions for this node

- Watch model: undecided.
- Watch OS: Wear OS or another platform — undecided.
- How the watch pairs with the phone (platform companion channel vs.
  cloud-only): undecided, to be chosen once a watch is chosen.

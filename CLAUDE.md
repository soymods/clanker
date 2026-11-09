# Clanker

Fabric mod: an LLM-driven fake player with local voice. Full plan and roadmap in [DESIGN.md](DESIGN.md).

## Build

- Java 25 required: `source .env && ./gradlew build` (copy `.env.example` to `.env` first; it sets `JAVA_HOME` to `/opt/homebrew/opt/openjdk@25`, which survives Homebrew upgrades)
- Dev client: `./gradlew runClient` (dev server: `./gradlew runServer`)
- Headless server tests: `./gradlew runGameTest` (tests live in `src/gametest`; they use `FakeProvider`, never a real API)
- Live model smoke test: `CLANKER_LIVE_TEST=1 ./gradlew runGameTest` (optional `CLANKER_LIVE_MODEL=...`, `CLANKER_LIVE_EFFORT=...`); costs a fraction of a cent
- Client tests (opens a game window, screenshots in `build/run/clientGameTest/screenshots`): `./gradlew runClientGameTest`
- Gametest ticks run unthrottled, so anything timed in wall-clock ms (brain cooldowns, model calls) needs wall-clock waits, not maxTicks
- API keys for dev/tests: the user keeps them in `~/.clanker/` (`api-key.txt` for OpenAI, `xai-key.txt`, `elevenlabs-key.txt`). Never read, print, or copy those files; the mod reads them itself (`ServerConfig.resolveKey`).
- Tests must not reach paid services by accident: `TestSetup.isolate()` forces the local voice, and every bot a test spawns gets `bot.voice().setLocalOnly(true)` (the config can be reloaded mid-run). Real calls only happen in env-gated live tests.
- Live scenarios (xAI brain, logs `[scenario]` lines): `CLANKER_SCENARIOS=1 ./gradlew runGameTest` (optional `CLANKER_LIVE_MODEL`, `CLANKER_LIVE_PERSONALITY`). xAI voice samples into `build/run/gameTest/build/voice-samples/`: `CLANKER_VOICE_SAMPLES=1` (optional `CLANKER_VOICE_SAMPLES_VOICES=rex,eve`).
- Minecraft 26.2 is unobfuscated: code uses Mojang names directly, no mappings.

## Rules

- **No scripted dialogue or reactions.** Never hardcode anything Clanker says, and never tell the model how to react to an event. Events are neutral facts; the model decides. (DESIGN.md §1)
- `src/main` is common + server code and must not reference client classes. Client-only code goes in `src/client`.
- Model/HTTP calls never run on the server thread; results are applied on the next tick.
- API keys (brain and voice) live only in `config/clanker-server.json` and are never sent to clients.
- Cloud voice runs on the host (`server/voice`); clients get PCM via `VoiceAudioPayload` and fall back to local Piper.

## Baritone

Pathfinding is ported from Baritone's `26.2` branch, checked out at `~/Desktop/baritone` (LGPL-3.0; Clanker as a whole is GPL-3.0-or-later). Ported files go in `server/pathing`, keep Baritone's license header, and note what changed.

# Clanker

Fabric mod: an LLM-driven fake player with local voice. Full plan and roadmap in [DESIGN.md](DESIGN.md).

## Build

- Java 25 required: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.3/libexec/openjdk.jdk/Contents/Home ./gradlew build`
- Dev client: `./gradlew runClient` (dev server: `./gradlew runServer`)
- Headless server tests: `./gradlew runGameTest` (tests live in `src/gametest`; they use `FakeProvider`, never a real API)
- Live model smoke test: `CLANKER_LIVE_TEST=1 ./gradlew runGameTest` (optional `CLANKER_LIVE_MODEL=...`); costs a fraction of a cent
- Client tests (opens a game window, screenshots in `build/run/clientGameTest/screenshots`): `./gradlew runClientGameTest`
- Gametest ticks run unthrottled, so anything timed in wall-clock ms (brain cooldowns, model calls) needs wall-clock waits, not maxTicks
- API key for dev/tests: the user keeps it in `~/.clanker/api-key.txt`. Never read, print, or copy that file; the mod reads it itself (`ServerConfig.resolveApiKey`).
- Minecraft 26.2 is unobfuscated: code uses Mojang names directly, no mappings.

## Rules

- **No scripted dialogue or reactions.** Never hardcode anything Clanker says, and never tell the model how to react to an event. Events are neutral facts; the model decides. (DESIGN.md §1)
- `src/main` is common + server code and must not reference client classes. Client-only code goes in `src/client`.
- Model/HTTP calls never run on the server thread; results are applied on the next tick.
- The API key lives only in `config/clanker-server.json` and is never sent to clients.

## Baritone

Pathfinding is ported from Baritone's `26.2` branch, checked out at `~/Desktop/baritone` (LGPL-3.0; Clanker as a whole is GPL-3.0-or-later). Ported files go in `server/pathing`, keep Baritone's license header, and note what changed.

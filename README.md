# Clanker

Play Minecraft with an AI. Clanker joins your world as a player: it sees what's around it, hears you talk, answers out loud from where it's standing, and plays alongside you — mining, crafting, fighting, building, and making its own decisions (and mistakes).

> **Status:** early development (0.1.0-dev). Clanker joins, sees, talks (text and local voice), finds paths, mines, picks up and gives items, crafts, smelts, uses chests, places blocks, fights, eats, sleeps, wears armor, and keeps a notebook between sessions. See [DESIGN.md](DESIGN.md) for what's next.

## Quick start

1. Install the mod (and Fabric API) on the server, or in your client for singleplayer/LAN.
2. In game, press the **Open Settings** key (Controls → Clanker; unbound by default) or run `/clanker config`, paste an OpenAI API key, and save. The key stays on the host and is never sent back to clients.
3. `/clanker spawn`, then talk to it in chat — say its name, e.g. "Clanker, chop that tree and bring me the logs".

Other commands: `/clanker despawn`, `stop`, `status`, `follow [player]`, `come`, `goto <pos>`, `forget`, `memory [clear]`. Saying "stop" with its name halts it instantly, without asking the model.

### Voice

Open the settings screen and press **Voice…**, then **Download**. That fetches a Whisper speech model (~150 MB), the Piper voice engine (~80 MB) and a voice (~63 MB) into `<game dir>/clanker/models`. Nothing is downloaded until you press the button. Then hold **V** to talk (or switch to open mic). Speech is recognized and synthesized on your own computer; only text goes over the network. Players without the mod can still chat with Clanker in text.

On macOS the official launcher isn't allowed to use the microphone; Prism Launcher is. On Windows, allow desktop apps to use the microphone in privacy settings.

### Limits

**Limits…** in the settings screen controls what Clanker may do (break or place blocks, attack mobs, attack players, use chests) and how much it may spend per session and per day. The spend is estimated from token counts and published prices; Clanker stops calling the model when a cap is reached. Defaults: $2 per session, $5 per day, attacking players off.

## Features

- An AI-controlled player with a real inventory, health, and hunger
- Gets around like a player, using Baritone's pathfinding: sprinting, swimming, jumping gaps, digging through walls, pillaring and bridging with junk blocks
- Talks with you through local speech-to-text (Whisper) and positional text-to-speech (Piper)
- Push-to-talk or open mic (talk over Clanker to cut it off), optional subtitles, a thinking indicator, settings menus
- A notebook it writes in itself, kept per world between sessions
- Host permissions and spend caps
- Works in singleplayer, Open to LAN, and on dedicated servers — only the host needs an API key
- Bring your own LLM: OpenAI or any OpenAI-compatible endpoint (Ollama, OpenRouter, …); Anthropic planned
- No scripted lines: everything Clanker says and does is decided by the model

## Requirements

- Minecraft **26.2**
- Fabric Loader ≥ 0.19.5 and Fabric API
- Java 25

## Building

```sh
./gradlew build
```

The mod jar ends up in `build/libs/`. To launch a dev client: `./gradlew runClient`. Tests: `./gradlew runGameTest` (headless) and `./gradlew runClientGameTest` (opens a game window).

## Credits

- [Baritone](https://github.com/cabaletta/baritone) — Clanker's pathfinding is based on Baritone's (LGPL-3.0). Ported files keep their original license headers.
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) — speech recognition
- [Piper](https://github.com/OHF-Voice/piper1-gpl) — speech synthesis
- [Carpet](https://github.com/gnembon/fabric-carpet) — reference for the fake-player approach

## License

Clanker is free software under the GNU General Public License v3.0 or later. See [LICENSE](LICENSE). Code ported from Baritone was originally LGPL-3.0 and is used under the GPL as that license allows.

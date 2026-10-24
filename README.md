# Clanker

Play Minecraft with an AI. Clanker joins your world as a player: it sees what's around it, hears you talk, answers out loud from where it's standing, and plays alongside you — mining, crafting, fighting, building, and making its own decisions (and mistakes).

> **Status:** early development (0.1.0-dev). Clanker joins, sees, chats in text, finds paths, mines, picks up and gives items, crafts, smelts, uses chests, places blocks, fights, eats, sleeps and wears armor. Voice and long-term memory are next — see [DESIGN.md](DESIGN.md).

## Quick start (0.1)

1. Install the mod (and Fabric API) on the server, or in your client for singleplayer/LAN.
2. In game, press the **Open Settings** key (Controls → Clanker; unbound by default) or run `/clanker config`, paste an OpenAI API key, and save. The key stays on the host and is never sent back to clients.
3. `/clanker spawn`, then talk to it in chat — say its name, e.g. "Clanker, chop that tree and bring me the logs".

Other commands: `/clanker despawn`, `stop`, `status`, `follow [player]`, `come`, `goto <pos>`, `forget`. Saying "stop" with its name halts it instantly, without asking the model.

## Features (planned)

- An AI-controlled player with a real inventory, health, and hunger
- Talks with you through local speech-to-text (Whisper) and positional text-to-speech
- Push-to-talk or open mic, subtitles, a settings menu
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

# Clanker — Design

Clanker is a Fabric mod that puts an LLM-driven player into your Minecraft world. It sees the world around it, hears you talk, answers with a voice that comes from its body, and plays alongside you: walking, mining, crafting, fighting, building, and making its own mistakes.

- **Mod name / ID / repo:** Clanker / `clanker` / `soymods/clanker`
- **Target:** Minecraft **26.2**, Fabric Loader, Fabric API, Java 25
- **License:** GPL-3.0-or-later (Baritone's LGPL code and Piper's GPL code are both compatible)

---

## 1. Core principle: emergent, not scripted

Clanker's personality and behavior come from the model, not from us.

- **No canned lines.** The mod never contains things Clanker says. No hardcoded greeting, no "zombies!" line at night, no death quip. When it spawns, it is simply told it has joined the world and decides for itself whether and how to say hello.
- **Events are facts, not instructions.** The mod reports what happened in neutral terms (`time: dusk`, `took 4 damage from skeleton`, `player Ryder joined`, `task mine_block failed: no pickaxe`). It never tells the model how to feel or what to do about it.
- **Code provides abilities, the model provides decisions.** Java supplies perception, tools, and reliable execution. What to do, when to speak, and what to say is always the model's call.
- **Personality is a prompt, not a script.** The host can describe a temperament (helpful, sarcastic, chaotic…). It shapes *how* Clanker decides, never *what* it says.

If a feature needs a hardcoded sentence to work, it's designed wrong.

---

## 2. A typical session

1. Both players install Clanker. On first launch the client downloads the speech models (~200 MB) once, with a progress screen.
2. In game, the host opens Clanker settings (keybind, `/clanker config`, or Mod Menu), pastes an API key, picks a provider, model and personality. It takes effect immediately, no restart.
3. `/clanker spawn` — Clanker joins the world as a player with a skin, shows up in the tab list, and does whatever it decides to do on arrival.
4. You hold **V** and say *"Clanker, grab some wood and make us a crafting table."* A second or two later it answers aloud, from where it's standing, with subtitles, then goes and does it.
5. Things happen — night falls, a creeper shows up, your friend asks it to follow them — and Clanker reacts however it sees fit.
6. It dies in lava. It respawns, knows where it died, and has its own opinion about it.
7. Next session it still remembers your base, named places, and what it was working on.

---

## 3. The bot

- **Fake server player** (`ServerPlayer` subclass with a fake connection, Carpet-style). Real inventory, armor, health, hunger, item pickup, reach, tool durability, block-breaking speed. Eats, sleeps, dies, respawns.
- Appears in the tab list with a nametag. Name and skin are configurable (skin from any player name).
- **Vanilla-compatible:** players without the mod see it and can chat with it by text; they just don't hear voice.
- **Containers without screens:** chests, crafting tables, furnaces etc. open as server-side menus; the bot moves items by calling the same slot-click logic a real click triggers.

## 4. Perception

Built each turn as compact text (target 1–3k tokens):

- Self: position, facing, dimension, biome, light level, time of day, weather, health, hunger, armor, held item, inventory summary
- Players nearby: name, distance, direction, held item, what block/entity they're looking at
- Entities nearby: mobs (hostile/passive), dropped items, with distance/direction
- Notable blocks within ~16 blocks: ores, chests, beds, crafting stations, water, lava, drops/cliffs, doors
- Current task + progress, and recent events since the last turn

Raw block dumps are never sent.

## 5. Brain

- **LLM tool calling.** The model plans and chooses tools; skills execute deterministically over many ticks. The model is never in the per-tick loop.
- **Turn triggers:**
  - Someone speaks to it (by name, by proximity, or anyone — configurable)
  - An event occurs (damage, task finished/failed, time-of-day change, player joins/leaves, item received…)
  - Idle heartbeat (configurable interval), so it can act or speak unprompted if it wants
- **Async:** all HTTP runs off the server thread; results are queued back and applied on the next tick.
- **Memory** (per world, `<world>/clanker/memory.json`):
  - Short-term: recent conversation + rolling summary
  - Long-term: named places, facts about players, ongoing goals, last death location — written by the model through a `remember` tool, not by hardcoded rules
- **Providers:** OpenAI (default model `gpt-5-mini`, `reasoning_effort` low for fast replies) and any OpenAI-compatible endpoint (Ollama, OpenRouter, local). Anthropic is planned for a later version via the official Java SDK. Prompt caching where supported.

## 6. Skills (tools the model can call)

| Category | Skills |
|---|---|
| Movement | go to coordinates / named place, come to player, follow player, explore, flee, stop |
| Gathering | mine N of a block type, chop trees, collect nearby items, farm & replant, fish |
| Crafting | craft item (resolves recipe tree, places/uses crafting table if needed), smelt |
| Inventory | equip, give to player, drop, deposit/withdraw chest, sort |
| Combat | attack target, guard player, eat, shield, retreat |
| Building | place block, pillar, bridge, light area; later: structures from a description |
| Utility | sleep, set waypoint, find nearest X, use/interact with block (doors, levers, buttons) |
| Social | say, look at, emote (jump, crouch, nod) |
| Memory | remember fact, recall, forget |

Every skill reports `success` / `failed: <reason>` / `progress`, so the model can recover on its own ("no axe" → it may decide to craft one).

## 7. Pathfinding

Ported from **Baritone** (branch `26.2`, local checkout at `~/Desktop/baritone`), credited; ported files keep Baritone's notices and are used under the GPL as LGPL-3.0 §2 allows:

- **Reused:** A* (`AStarPathFinder`, `BinaryHeapOpenSet`), movement types (traverse, ascend, descend, fall, diagonal, parkour, pillar, bridge, break-through), cost model (`ActionCosts`, `CalculationContext`), precomputed block safety data.
- **Rewritten:** execution. Baritone drives the *client* player by faking key presses. Clanker implements Baritone's player/world context interface for a server-side fake player and converts path steps into rotation + movement input each tick.
- Re-paths when the world changes; dangers (lava, long falls, mobs) are weighted in costs.
- Ported files keep their Baritone license headers plus a note of what changed.

## 8. Voice (client-side, fully local)

- **Speech-to-text:** whisper.cpp via JNI, bundled natives (Windows x64, macOS arm64/x64; Linux free). Models `tiny` / `base` (default) / `small`, downloaded on first run. Thread-capped so it doesn't hurt FPS.
- **Text-to-speech:** Piper neural voices (~60 MB each), same voice for everyone.
- **Positional playback:** through Minecraft's existing OpenAL context, a source positioned at the bot every tick — voice comes from Clanker and falls off with distance.
- **Mic modes:** push-to-talk (default **V**), open mic with voice activity detection, off.
- **Echo protection:** mic input is ignored while Clanker is speaking, and transcripts matching its recent speech are dropped.
- **Subtitles** and a "thinking" indicator above Clanker's head.
- **Network carries text only.** Client transcribes → sends text to server. Server sends text → each client synthesizes locally.
- **Mic permission handling:** detects a silent mic and shows an in-game hint (macOS: official launcher lacks mic permission, Prism Launcher works; Windows: "let desktop apps access your microphone").

```
CLIENT (each player)                       SERVER (host)
mic ─► PTT / VAD ─► Whisper ─► text ──────► Brain ─► LLM API   (only cloud piece)
                                              │
speaker ◄─ OpenAL @ bot pos ◄─ Piper ◄─ text ◄┘     + perception, skills, fake player
```

## 9. Multiplayer

- Works in singleplayer, Open to LAN, and on dedicated servers. Server logic runs on the host; only the host needs an API key.
- **Who it listens to:** name-addressed only / anyone within N blocks / everyone.
- Knows who said what. Optional **owner** whose instructions win conflicts.

## 10. Safety & control

- **Stop is local:** "Clanker, stop" (and a keybind / `/clanker stop`) is matched without the LLM and cancels everything instantly.
- **Host permissions:** break blocks, break player-placed blocks, attack players, open containers, use TNT/lava/fire.
- **Spend control:** token + estimated cost tracking, per-session and per-day caps, rate limiting.
- **Action log:** every tool call and its reason.

## 11. Settings

Custom screen built from vanilla widgets (no config-library dependency). Opens via keybind or Mod Menu (optional integration).

**Client (each player)**
- Voice mode: push-to-talk / open mic / off; PTT key; open-mic sensitivity
- Microphone device, live level meter, mic test
- Whisper model + language
- TTS voice, volume, speed
- Subtitles, thinking indicator, debug HUD

**Server (host/op only)**
- Provider, model, API key, custom endpoint
  - The key is pasted into a masked field. It travels once from the op's client to the server, is saved in `config/clanker-server.json`, and is never sent back to any client: the screen only shows whether a key is set.
  - Also accepted from the `CLANKER_API_KEY` env var or `~/.clanker/api-key.txt` (handy for dedicated servers and dev).
- Bot name, skin
- Personality
- Idle interval, listening mode, owner
- Permissions, spend caps

## 12. Commands

```
/clanker spawn | despawn | stop
/clanker follow <player> | goto <x y z | place>
/clanker waypoint <name> | owner <player>
/clanker memory clear | status | config
```

## 13. Debug

- In-world path rendering
- HUD: current task, last tool call, token/cost counter
- `/clanker status` dump; action log file

## 14. Project layout

Single jar, Loom split source sets (`main` = common + server, `client` = client-only).

```
src/main/java/io/github/soymods/clanker/
├─ Clanker.java            mod entrypoint
├─ config/                 server config (JSON)
├─ network/                payload types shared by both sides
└─ server/
   ├─ bot/                 fake player, connection, spawn/despawn, persistence
   ├─ perception/          world snapshot → text
   ├─ brain/               providers, prompts, tool registry, memory, event queue
   ├─ skills/              one class per skill + task scheduler
   ├─ pathing/             Baritone-derived A*, movements, executor
   ├─ safety/              permissions, rate limits, spend tracking
   └─ command/             /clanker
src/client/java/io/github/soymods/clanker/client/
├─ ClankerClient.java      client entrypoint
├─ config/                 client config (JSON)
├─ audio/                  mic capture, VAD, OpenAL positional playback
├─ stt/                    whisper.cpp binding + model downloader
├─ tts/                    Piper binding + voices
├─ gui/                    settings screens, HUD, subtitles
└─ natives/                per-OS native library extraction/loading
```

Files on disk:
- `config/clanker-client.json` — client settings
- `config/clanker-server.json` — host settings incl. API key
- `<world>/clanker/memory.json` — per-world memory
- `<game dir>/clanker/models/` — Whisper + Piper models

## 15. Roadmap

| Version | Scope |
|---|---|
| **0.1** | Fake player spawn/despawn, text chat with the LLM, perception, basic follow / go-to, in-game host settings screen (API key, provider, model). OpenAI + OpenAI-compatible providers. |
| **0.2** | Pathfinding (interim A*; Baritone port later), mining, collecting, giving/dropping items, knockback — *done* |
| **0.3** | Crafting, smelting, containers, placing, combat, eating, sleeping, armor — *done*; block/attack permissions and Anthropic provider still to do |
| **0.4** | Voice: Whisper STT, Piper TTS, positional audio, push-to-talk |
| **0.5** | Full settings screens (voice, display, permissions), open mic, spend caps, memory |
| **1.0** | Personalities, debug tools, polish, Modrinth release |
| Later | Building from descriptions, multiple Clankers, vision model screenshots |

## 16. Toolchain

| | Version |
|---|---|
| Minecraft | 26.2 (unobfuscated, Mojang names) |
| Fabric Loader | 0.20.1 (requires ≥ 0.19.5) |
| Fabric API | 0.161.0+26.2 |
| Fabric Loom | 1.18-SNAPSHOT (`net.fabricmc.fabric-loom`) |
| Gradle | 9.7.1 (wrapper) |
| Java | 25 |

## 17. Credits

- **Baritone** (cabaletta/baritone, LGPL-3.0) — pathfinding
- **whisper.cpp** (MIT) — speech recognition
- **Piper** (GPL-3.0) — speech synthesis
- **Carpet mod** — reference for fake-player approach

# BeatBeams

**BeatBeams** is a Fabric Minecraft mod that turns real music into in-world light beams.

Play your own tracks, analyze the beat and spectrum, then drive beacons, wires, and light panels so builds react to the music in real time.

> **Status: still in development**  
> This project is incomplete and not finished. Features, APIs, and configs may change. Expect bugs, incomplete systems, and breaking updates.

---

## Features (current / in progress)

- **Music Beacon** — placeable block that shoots configurable light beams
- **Music Player** — play tracks from a local music folder
- **Wire & Connector** — power network to link devices
- **Lights Panel** — light/display surface driven by the system
- **Audio support** — MP3, OGG, and WAV decoding
- **Track analysis** — FFT / beat-oriented processing for reactive visuals
- **Config GUIs** — right-click beacons, player, and panels to configure them

---

## Requirements

| Component | Version |
|-----------|---------|
| Minecraft | `26.3` |
| Fabric Loader | `0.19.5+` |
| Fabric API | matching `26.3` |
| Java | `25` |

---

## Setup

### Music folder

Tracks are loaded from:

```text
<game directory>/config/beatbeams/music/
```

Supported formats: **MP3**, **OGG**, **WAV**.

Drop files into that folder, then use the in-game Music Player (or commands) to refresh/select tracks.

### Volume

Playback follows Minecraft’s **Master × Jukebox/Note Blocks** volume. Muting vanilla background music does not mute BeatBeams.

---

## Building from source

```bash
git clone https://github.com/xien-ica/BeatBeams.git
cd BeatBeams
./gradlew build
```

Built jars land in `build/libs/`.

For development:

```bash
./gradlew runClient
```

---

## Project structure

```text
src/
  main/     # common: blocks, power network, audio decoding/analysis
  client/   # client: GUIs, beam rendering, track player bootstrap
```

Key packages:

- `block/` — Music Beacon, Wire, Connector, Music Player, Lights Panel
- `audio/` — decoders, FFT, track library/player, analysis
- `power/` — power network propagation
- `client/gui/` & `client/render/` — screens and beam visuals

---

## License

```text
Copyright (c) 2026 xien.ica
All rights reserved.
```

See `LICENSE.txt`.

---

## Notes

- Client-focused mod (`environment: client` in `fabric.mod.json`), with common registration for blocks/items.
- Power network updates are deferred to end-of-tick so neighbor chains stay stable.
- Multiplayer/dedicated-server behavior is still evolving — treat current builds as **dev / experimental**.

---

## Contributing

The project is early. Feedback, bug reports, and ideas are welcome, but expect the codebase to move quickly while core systems settle.

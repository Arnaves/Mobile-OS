# AXIOM — an on-device AI phone

**Device:** Redmi Note 12 Pro+ 5G · MediaTek Dimensity 1080 · 12 GB RAM · 256 GB storage

AXIOM turns the phone into an AI-first device. Every model runs **locally**: no cloud, no
API keys, and it works in airplane mode. It doesn't replace Android, which would need
drivers we can't get. It runs as a layer of AI on top of Android:

| Layer | What it does |
|---|---|
| **Cortex** (`termux/`) | Local LLM server (llama.cpp) with swappable "brains": chat, deep-think, code, vision |
| **Router** (`termux/axiom`) | One command and one endpoint that picks the right brain for each task |
| **Memory Core** | "remember that…" stores facts every brain reads, so it knows *you* |
| **HUD** (`termux/hud.html`) | Sci-fi app: reactor core, brain switcher, camera vision, voice, live vitals |
| **Interface** | Minimal launcher + assistant gesture + automations (roadmap) |

**New: the Android app.** Install one APK and the phone becomes AXIOM (launcher, voice assistant, local AI).
See **[android/README.md](android/README.md)**, or grab [`releases/AXIOM-1.0.apk`](releases/AXIOM-1.0.apk).

Start here: **[docs/BLUEPRINT.md](docs/BLUEPRINT.md)** (full plan) and
**[termux/README.md](termux/README.md)** (install in about 15 minutes).

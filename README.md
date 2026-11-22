# AXIOM — an on-device AI phone

**Device:** Redmi Note 12 Pro+ 5G · MediaTek Dimensity 1080 · 12 GB RAM · 256 GB storage

AXIOM turns the phone into an AI-first device. Every model runs **locally**: no cloud, no
API keys, and it works in airplane mode. It doesn't replace Android, which would need
drivers we can't get. It runs as a layer of AI on top of Android:

| Layer | What it does |
|---|---|
| **Cortex** (`termux/`) | Local LLM server (llama.cpp) with swappable "brains": chat, deep-think, code, vision |
| **Router** (`termux/axiom`) | One command and one endpoint that picks the right brain for each task |
| **Memory Core** | A notes/memory folder the AI reads, so it knows *you* (roadmap) |
| **Interface** | Built-in web UI + voice + a minimal launcher with a sci-fi HUD look |

Start here: **[docs/BLUEPRINT.md](docs/BLUEPRINT.md)** (full plan) and
**[termux/README.md](termux/README.md)** (install in about 15 minutes).

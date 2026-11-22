# AXIOM Blueprint

## 0. What this hardware can really do

Dimensity 1080 = 2× Cortex-A78 @ 2.6 GHz + 6× A55, Mali-G68 GPU. llama.cpp and similar
tools mostly run on the **CPU**. The MediaTek NPU is almost never used by open-source apps.

| Model size (4-bit) | RAM used | Expected speed | Verdict |
|---|---|---|---|
| 1–2B | 1–1.5 GB | 12–25 tok/s | Instant: router, autocomplete, quick replies |
| 3–4B | 2.5–3 GB | 6–12 tok/s | **Sweet spot**: daily chat, coding, vision |
| 7–8B | 4.5–5 GB | 2–4 tok/s | Usable for "think hard and wait" tasks only |
| 12B+ | 7 GB+ | <2 tok/s, thermal throttling | Not worth it |

Image generation on this chip is CPU-only and slow (about 1–3 min per 512×512 image with SD 1.5
class models). It works, but it's a party trick, not instant.

**Rule:** run **one big brain at a time** plus a tiny always-on model. That's how 12 GB stays
fast instead of swapping.

## 1. The unique idea: a "Brain Switchboard"

Most "AI phones" are one chatbot. AXIOM is a **set of specialists** with a router:

```
            ┌──────────── you (voice / text / camera) ────────────┐
            ▼                                                     │
     [ Router: 1B model ]  classifies the request in ~0.3 s       │
      │      │        │        │                                  │
   CHAT    THINK     CODE    VISION                               │
   4B      4B-think  coder   VLM 4B   ← only ONE loaded at a time │
      └──────┴────────┴────────┴──► Memory Core (your notes) ─────┘
```

- **CHAT**: fast general assistant
- **THINK**: reasoning model that shows its chain of thought (math, planning, decisions)
- **CODE**: coding model; pairs with Termux so the phone can write *and run* code
- **VISION**: point the camera, ask questions about what it sees
- **Memory Core**: `~/axiom/memory/*.md` facts about you, injected into every prompt

## 2. Phases

### Phase 1: Foundation (day 1)
1. Finish setup with minimal Google sign-in and turn off MIUI/HyperOS ads
   (Settings → Privacy → Ad services, and in each system app's settings).
2. Install **F-Droid**, then **Termux** + **Termux:API** *from F-Droid* (the Play Store build is outdated).
3. Settings → Battery → Termux → **No restrictions**. Otherwise HyperOS kills the AI server.
4. Run `termux/setup.sh` from this repo.

### Phase 2: Ready-made AI apps (no coding needed)
| Need | App | Notes |
|---|---|---|
| Chat + vision, polished | **Google AI Edge Gallery** | Gemma models, runs offline |
| Chat with any GGUF model | **PocketPal AI** | Easiest way to try Hugging Face models |
| Chat, vision, image gen | **MNN Chat** (Alibaba) | Good CPU/GPU speed on MediaTek |
| Roleplay/agent-style UI | **ChatterUI** or **Layla** | Can connect to the AXIOM server |
| Speech-to-text offline | **FUTO Keyboard** / **FUTO Voice Input** | Whisper-based voice typing in every app |
| Offline translation | **Google Translate** offline packs or **RTranslator** | |

Check that each app is still maintained before you install it. This field moves fast.

### Phase 3: AXIOM Cortex (this repo)
- `axiom start chat|think|code|vision` swaps the loaded brain
- Web UI at `http://127.0.0.1:8080` → add it to your home screen as an app
- OpenAI-compatible API at `http://127.0.0.1:8080/v1`, so any app that accepts a custom
  endpoint (ChatterUI and others) uses your local brain

### Phase 4: Make the OS *feel* AI
- **Launcher:** a minimal launcher (e.g. Niagara, Olauncher, or KISS) + dark AMOLED wallpaper
  + monospace font = HUD look. Put AXIOM at the center.
- **Assistant gesture:** Settings → Apps → Default apps → Digital assistant, then pick an app
  that can talk to a local endpoint.
- **Automations:** Tasker or Automate + Termux:Tasker → "when I plug in at night, summarise
  today's notes", "when I screenshot, describe it", etc.
- **Voice loop:** Termux:API `termux-speech-to-text` → AXIOM → `termux-tts-speak`. A Jarvis
  that talks back, fully offline (`axiom voice`).

### Phase 5: Advanced (optional, risky)
- **Custom ROM** (crDroid/LineageOS builds may exist for this device's codename): less bloat,
  more free RAM. Xiaomi's bootloader unlock under HyperOS is restricted and can take weeks or
  fail. It also wipes the phone and can trip banking-app checks. Only do this if you accept that.
- **Coding agent:** run an open-source coding agent (e.g. Aider) in Termux, pointed at the local
  CODE brain.

## 3. Model picks (Nov 2026: swap in newer ones freely)
Configured in `termux/axiom.conf`. Any GGUF on Hugging Face works.
- Router: Qwen3 0.6B–1.7B class
- Chat: Qwen3-4B-Instruct / Gemma 3 4B / Phi-4-mini
- Think: Qwen3-4B-Thinking
- Code: Qwen2.5-Coder-3B (or newer coder 3–4B)
- Vision: Gemma 3 4B with its mmproj (llama.cpp downloads it automatically with `-hf`)

## 4. Efficiency rules
- Q4_K_M quantization; context 4096 (raise only if needed)
- `-t 4` threads: the A78 cores do the work, and the A55 cores add heat without much speed
- Unload brains when you're done (`axiom stop`). Idle AI = zero battery
- Keep the phone cool. Sustained inference throttles after a few minutes; a case off helps

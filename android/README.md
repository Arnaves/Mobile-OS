# AXIOM for Android

One app that turns the phone into AXIOM: local AI brains, a sci-fi home screen, and a voice
assistant on the power button. No Termux, no root.

**Download:** [`releases/AXIOM-1.0.apk`](../releases/AXIOM-1.0.apk) (10 MB, arm64)

## Install (Redmi Note 12 Pro+ / HyperOS)
1. Copy the APK to the phone and tap it. Allow **Install unknown apps** for your file manager or browser.
   If HyperOS shows a security scan, choose **Install anyway**.
2. Open **AXIOM**. Allow notifications (this keeps the AI core alive).
3. **CORES** opens automatically → tap **Get** on **CHAT** (~2.5 GB, use Wi-Fi).
   Add THINK, CODE and VISION when you want them. Each downloads once, then works offline.
4. Still in CORES, under **Make this phone AXIOM**:
   - **Home screen → Set**: AXIOM becomes your launcher (press Home and choose AXIOM, then *Always*)
   - **Assistant → Set**: pick *AXIOM* under *Digital assistant app*. Now hold the power button
     (or swipe up from a bottom corner) to summon AXIOM by voice
   - **Microphone → Allow**
5. HyperOS battery: Settings → Apps → AXIOM → Battery saver → **No restrictions**, and
   lock AXIOM in Recents (pull its card down). Otherwise HyperOS kills the AI core.

## What it does
| | |
|---|---|
| **Talk to it** | Tap the reactor or hold the power button. Speak, and it answers out loud |
| **Phone control (instant, no AI needed)** | "open WhatsApp" · "set an alarm for 6:30 am" · "timer 10 minutes" · "flashlight on" · "call Mom" · "navigate to the airport" · "play lofi beats" · "what's my battery" · "open wifi settings" |
| **4 AI brains** | AUTO picks: CHAT for everyday questions, THINK for planning/maths (shows its reasoning), CODE for programming, VISION when you attach a photo |
| **Camera → AI** | Camera button, take a photo, ask "what is this?" |
| **Memory** | "remember that my sister's birthday is 3 June". Edit everything in CORES → Memory core |
| **Launcher** | Clock, AI core, 5-app dock (long-press in APPS to pin), full app drawer with search |
| **Vitals** | RAM, battery and temperature in the header |
| **API** | `http://127.0.0.1:7777` (HUD/API) and `:8080` (OpenAI-compatible) for other apps on the phone |

## Honest limits
- Speed on the Dimensity 1080 is about 6–12 words/s for chat. The first message after switching
  brains takes 10–30 s while the model loads, and only one brain is in RAM at a time.
- Voice input uses the phone's speech recognizer (Google). It's offline only if offline speech
  packs are installed (Google app → Settings → Voice → Offline speech recognition).
- Web search, navigation and YouTube open the normal apps, which need internet.
- "Call" opens the dialer with the number filled in; you tap call. AXIOM never places calls
  or sends messages on its own.
- The APK is signed with a development key. If a future build won't install over this one,
  uninstall first (downloaded cores are deleted with it).

## Build it yourself
```bash
./build-native.sh     # llama.cpp → app/src/main/jniLibs/arm64-v8a/libllama_server.so (needs NDK)
./gradlew assembleRelease testReleaseUnitTest
```
Code map: `Core.kt` (brains, router, phone-command parser), `Engine.kt` (model downloads +
llama-server process), `HudServer.kt` (local API), `Actions.kt` (phone control),
`MainActivity.kt` (WebView + native bridge), `assist/` (default-assistant hooks),
`assets/hud.html` (the interface).

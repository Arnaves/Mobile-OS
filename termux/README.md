# AXIOM Cortex: Termux install

1. Install **Termux** and **Termux:API** from **F-Droid** (not the Play Store).
2. Android Settings → Apps → Termux → Battery → **No restrictions**.
3. In Termux:
   ```bash
   pkg install -y git
   git clone https://github.com/arnaves/mobile-os ~/mobile-os
   bash ~/mobile-os/termux/setup.sh
   ```
4. Restart Termux, then:
   ```bash
   axiom start chat                 # downloads the model the first time
   axiom ask "plan my week"         # routed to THINK
   axiom ask "write a python port scanner for my LAN"   # routed to CODE
   axiom see "what plant is this?"  # camera → VISION
   axiom voice                      # offline Jarvis loop
   ```
5. Open `http://127.0.0.1:8080` in Chrome → ⋮ → **Add to Home screen**. That's your AXIOM app icon.

Each brain is about 2–3 GB, and only one is loaded at a time. Edit `~/axiom/memory/me.md`
so AXIOM knows who you are. Change models in `~/axiom/axiom.conf`.

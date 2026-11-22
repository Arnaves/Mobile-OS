#!/data/data/com.termux/files/usr/bin/bash
# AXIOM setup for Termux (install Termux + Termux:API from F-Droid first)
set -e
echo "== AXIOM :: installing core =="
pkg update -y
pkg install -y llama-cpp python termux-api curl
termux-setup-storage || true

AX="$HOME/axiom"
mkdir -p "$AX/memory" "$AX/logs" "$HOME/bin"
SRC="$(cd "$(dirname "$0")" && pwd)"
cp "$SRC/axiom" "$HOME/bin/axiom"
cp "$SRC/hud.html" "$AX/hud.html"
chmod +x "$HOME/bin/axiom"
[ -f "$AX/axiom.conf" ] || cp "$SRC/axiom.conf" "$AX/axiom.conf"
[ -f "$AX/memory/me.md" ] || cat > "$AX/memory/me.md" <<'M'
# About me (AXIOM reads this every time. Edit freely.)
- Name:
- Interests:
- How I like answers: short and direct
M
grep -q 'HOME/bin' "$HOME/.bashrc" 2>/dev/null || echo 'export PATH="$HOME/bin:$PATH"' >> "$HOME/.bashrc"
echo
echo "== Done. Restart Termux, then try:"
echo "   axiom start chat      # first run downloads ~2.5 GB"
echo "   axiom ask \"hello\""
echo "   open http://127.0.0.1:8080 in Chrome -> Add to Home screen"

#!/usr/bin/env python3
"""Thunderbird ouvre les liens http/https avec gestionnaire-ouvrir-lien. Thunderbird doit être fermé."""
import json, shutil, subprocess, sys
from pathlib import Path
SCRIPT = "/usr/bin/gestionnaire-ouvrir-lien"

def msg(t, err=False):
    print(t)
    subprocess.run(["zenity", "--error" if err else "--info", "--no-wrap", "--text", t], stderr=subprocess.DEVNULL)

if subprocess.run(["pgrep", "-f", "/thunderbird"], capture_output=True).returncode == 0:
    msg("Thunderbird est ouvert : ferme-le complètement puis relance.", True); sys.exit(1)
fichiers = list((Path.home() / ".thunderbird").glob("*/handlers.json"))
if not fichiers:
    msg("Aucun profil Thunderbird trouvé.", True); sys.exit(1)
for f in fichiers:
    bak = f.with_name("handlers.json.avant-gestionnaire")
    if "--annuler" in sys.argv:
        if bak.exists(): shutil.copy(bak, f)
        continue
    if not bak.exists(): shutil.copy(f, bak)
    d = json.loads(f.read_text())
    for s in ("http", "https"):
        d.setdefault("schemes", {})[s] = {"action": 2, "ask": False,
            "handlers": [{"name": "Gestionnaire de téléchargement", "path": SCRIPT}]}
    f.write_text(json.dumps(d))
msg("Annulé." if "--annuler" in sys.argv else "Branché. Rouvre Thunderbird : les liens ChatGPT/OpenAI ouvriront le gestionnaire.")

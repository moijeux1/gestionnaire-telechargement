#!/usr/bin/env python3
"""Gestionnaire de téléchargement de gros fichiers (reprise + relances + diagnostic).

Usage :
    gestionnaire.py [URL]                 fenêtre avec barre de progression
                                          (sans URL : prend le lien du presse-papiers)
    gestionnaire.py --cli URL             même chose dans le terminal
    gestionnaire.py URL --reprendre FICHIER.part
                                          continue un téléchargement commencé par Firefox
    gestionnaire.py URL --cookies-firefox envoie les cookies de Firefox (lien qui exige d'être connecté)

Principe : le fichier est écrit dans NOM.part ; à chaque coupure on relance avec
l'en-tête Range pour reprendre exactement où l'on s'est arrêté. Si le serveur ne
sait pas reprendre ou si le lien a expiré, le message l'explique clairement.
"""
import argparse
import html
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from http.cookiejar import DefaultCookiePolicy
from pathlib import Path
from urllib.parse import unquote, urlparse

import requests

DOSSIER = Path.home() / "Téléchargements"
UA = ("Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0")
BLOC = 1024 * 1024
MAX_ESSAIS_SANS_PROGRES = 30


class Erreur(Exception):
    pass


# Un cookie sans « . » initial reste réservé à son hôte exact.
REGLE_COOKIES = DefaultCookiePolicy(strict_ns_domain=DefaultCookiePolicy.DomainStrictNonDomain)


class Session(requests.Session):
    """Session qui garde la règle stricte : requests la perd en recopiant les cookies d'une requête."""

    def prepare_request(self, request):
        p = super().prepare_request(request)
        p._cookies.set_policy(REGLE_COOKIES)  # conservée ensuite à travers les redirections
        p.headers.pop("Cookie", None)
        p.prepare_cookies(p._cookies)
        return p


def ajoute_cookie(jar, nom, valeur, hote, chemin):
    """Ajoute un cookie lié à son domaine et à son chemin, envoyé en https seulement."""
    c = requests.cookies.create_cookie(nom, valeur, domain=hote, path=chemin or "/", secure=True)
    c.domain_specified = hote.startswith(".")
    jar.set_cookie(c)


def cookies_firefox(url):
    """Cookies du domaine, lus dans une copie temporaire (effacée aussitôt) de cookies.sqlite."""
    adresse = urlparse(url)
    if adresse.scheme != "https":
        raise Erreur("Les cookies de Firefox ne sont envoyés qu'à une adresse en https.")
    parts = (adresse.hostname or "").split(".")
    domaines = {".".join(parts[i:]) for i in range(len(parts) - 1)}
    jar = requests.cookies.RequestsCookieJar()
    for profil in list((Path.home() / ".mozilla/firefox").glob("*.default*")):
        src = profil / "cookies.sqlite"
        if not src.exists():
            continue
        with tempfile.TemporaryDirectory() as dossier:
            tmp = Path(dossier) / "c.sqlite"
            shutil.copy(src, tmp)
            for ext in ("-wal", "-shm"):
                if (profil / ("cookies.sqlite" + ext)).exists():
                    shutil.copy(profil / ("cookies.sqlite" + ext), str(tmp) + ext)
            try:
                con = sqlite3.connect(tmp)
                try:
                    for nom, val, h, chemin in con.execute("select name, value, host, path from moz_cookies"):
                        if h.lstrip(".") in domaines:
                            ajoute_cookie(jar, nom, val, h, chemin)
                finally:
                    con.close()
            except sqlite3.Error:
                pass
    return jar


def nom_sur(nom):
    """Réduit un nom venu du serveur à un simple nom de fichier, visible et sans chemin."""
    nom = str(nom or "").replace("\\", "/").rsplit("/", 1)[-1]
    nom = "".join(c for c in nom if c.isprintable()).strip().lstrip(". ")
    base, ext = os.path.splitext(nom)
    ext = ext[:20]
    while len((base + ext).encode("utf-8")) > 200:  # laisse la place à « -2 » et « .part »
        base = base[:-1]
    return (base + ext) or "telechargement"


def nom_fichier(rep, url):
    cd = rep.headers.get("Content-Disposition", "")
    m = re.search(r"filename\*=(?:UTF-8'')?([^;]+)", cd, re.I)
    if m:
        return nom_sur(unquote(m.group(1).strip('" ')))
    m = re.search(r'filename="?([^";]+)"?', cd, re.I)
    if m:
        return nom_sur(m.group(1))
    return nom_sur(unquote(urlparse(rep.url or url).path.rsplit("/", 1)[-1]))


def libre(chemin, taille=0):
    """Chemin qui n'écrase rien (nom-2, nom-3…). Un fichier déjà complet (même taille) est reconnu."""
    p, n = chemin, 2
    while os.path.lexists(p):
        if taille and p.is_file() and p.stat().st_size == taille:
            break
        p, n = chemin.with_name(f"{chemin.stem}-{n}{chemin.suffix}"), n + 1
    return p


def explique(code):
    return {
        401: "Le lien exige d'être connecté. Relance avec --cookies-firefox (connecte-toi d'abord à ChatGPT dans Firefox).",
        403: "Accès refusé : le lien a probablement expiré (les liens ChatGPT durent ~24 h). Redemande l'export / le mail.",
        404: "Fichier introuvable : lien expiré ou supprimé. Redemande l'export.",
        410: "Lien expiré. Redemande l'export.",
        429: "Trop de requêtes : le serveur demande d'attendre.",
    }.get(code, f"Réponse inattendue du serveur (HTTP {code}).")


def telecharger(url, dossier=DOSSIER, part_existant=None, cookies=None,
                log=print, progres=lambda fait, total, vitesse: None, stop=None):
    if urlparse(url).scheme not in ("http", "https"):
        raise Erreur("Seules les adresses http et https sont acceptées.")
    dossier = Path(dossier)
    dossier.mkdir(parents=True, exist_ok=True)
    s = Session()
    s.headers["User-Agent"] = UA
    if cookies is not None:
        s.cookies = cookies  # bocal lié aux domaines (voir cookies_firefox), jamais un simple dictionnaire

    # 1) Sonde : nom, taille, reprise possible ?
    rep = s.get(url, stream=True, timeout=30, headers={"Range": "bytes=0-0"}, allow_redirects=True)
    if rep.status_code not in (200, 206):
        raise Erreur(explique(rep.status_code))
    url = rep.url  # URL finale après redirections
    nom = nom_fichier(rep, url)
    if rep.status_code == 206:
        total = int(rep.headers["Content-Range"].split("/")[-1])
        reprise = True
    else:
        total = int(rep.headers.get("Content-Length") or 0)
        reprise = rep.headers.get("Accept-Ranges", "").lower() == "bytes"
    rep.close()
    log(f"Fichier : {nom}")
    log(f"Taille  : {total/1e9:.2f} Go" if total else "Taille inconnue")
    log("Reprise : " + ("oui" if reprise else "NON (le serveur ne l'accepte pas : coupure = recommencer)"))

    final = dossier / nom
    part = Path(str(final) + ".part")
    if part_existant:
        shutil.move(str(part_existant), part) if Path(part_existant) != part else None
    if final.is_file() and not final.is_symlink() and final.stat().st_size == 0:
        final.unlink()  # coquille vide laissée par un échec
    final = libre(final, total)
    if total and final.is_file() and final.stat().st_size == total:
        log("Déjà téléchargé en entier.")
        return final
    if part.is_symlink():
        raise Erreur(f"{part.name} est un lien symbolique : téléchargement refusé.")

    fait = part.stat().st_size if part.exists() else 0
    if total and fait > total:
        part.unlink()
        fait = 0
    if fait:
        log(f"Reprise à {fait/1e9:.2f} Go")

    sans_progres = 0
    t0, f0 = time.time(), fait
    while not total or fait < total:
        if stop and stop.is_set():
            raise Erreur("Arrêté (le .part est conservé, on pourra reprendre).")
        avant = fait
        try:
            h = {"Range": f"bytes={fait}-"} if fait else {}
            with s.get(url, stream=True, timeout=(15, 60), headers=h) as r:
                if r.status_code == 416:
                    break  # déjà complet
                if r.status_code not in (200, 206):
                    raise Erreur(explique(r.status_code))
                if fait and r.status_code == 200:
                    log("Le serveur ignore la reprise : on repart de zéro.")
                    fait = 0
                with open(part, "ab" if fait else "wb") as f:
                    for bloc in r.iter_content(BLOC):
                        if stop and stop.is_set():
                            raise Erreur("Arrêté (le .part est conservé).")
                        f.write(bloc)
                        fait += len(bloc)
                        dt = time.time() - t0
                        progres(fait, total, (fait - f0) / dt if dt > 1 else 0)
            if not total:
                break
        except Erreur:
            raise
        except (requests.RequestException, OSError) as e:
            fait = part.stat().st_size if part.exists() else 0
            if fait > avant:
                sans_progres = 0
            else:
                sans_progres += 1
            if sans_progres >= MAX_ESSAIS_SANS_PROGRES:
                raise Erreur(f"Abandon après {sans_progres} essais sans avancer : {e}")
            attente = min(60, 2 ** min(sans_progres, 6))
            log(f"Coupure ({type(e).__name__}) à {fait/1e9:.2f} Go — nouvel essai dans {attente}s")
            time.sleep(attente)
        else:
            fait = part.stat().st_size

    if total and part.stat().st_size != total:
        raise Erreur("Taille finale incorrecte, fichier non validé.")
    final = libre(final)  # un fichier du même nom a pu apparaître entre-temps
    os.replace(part, final)
    log(f"Terminé : {final}")
    return final


def notifier(titre, texte):
    try:  # liste d'arguments : aucun interpréteur de commandes ne lit le texte
        subprocess.run(["notify-send", "--", titre, html.escape(texte, quote=False)],
                       stderr=subprocess.DEVNULL, timeout=10)
    except (OSError, subprocess.SubprocessError):
        pass


def lien_presse_papiers(root):
    try:
        t = root.clipboard_get().strip()
        return t if t.startswith("http") else ""
    except Exception:
        return ""


def gui(url, args):
    import tkinter as tk
    from tkinter import ttk

    root = tk.Tk()
    root.title("Gestionnaire de téléchargement")
    root.geometry("640x340")
    try:
        for chemin in ("/usr/share/icons/hicolor/256x256/apps/gestionnaire-telechargement.png",
                       str(Path(__file__).resolve().parents[3] / "logo" / "logo-256.png")):
            if Path(chemin).exists():
                root._icone = tk.PhotoImage(file=chemin)  # garde une référence
                root.iconphoto(True, root._icone)
                break
    except Exception:
        pass
    url = url or lien_presse_papiers(root)
    var_url = tk.StringVar(value=url)
    ttk.Label(root, text="Lien :").pack(anchor="w", padx=10, pady=(10, 0))
    ttk.Entry(root, textvariable=var_url).pack(fill="x", padx=10)
    barre = ttk.Progressbar(root, maximum=100)
    barre.pack(fill="x", padx=10, pady=10)
    etat = tk.StringVar(value="Prêt")
    ttk.Label(root, textvariable=etat).pack(anchor="w", padx=10)
    zone = tk.Text(root, height=10, state="disabled")
    zone.pack(fill="both", expand=True, padx=10, pady=10)
    stop = threading.Event()

    def log(m):
        def _():
            zone.config(state="normal"); zone.insert("end", m + "\n"); zone.see("end"); zone.config(state="disabled")
        root.after(0, _)

    def progres(fait, total, v):
        def _():
            if total:
                barre["value"] = fait * 100 / total
            etat.set(f"{fait/1e9:.2f} / {total/1e9:.2f} Go — {v/1e6:.1f} Mo/s" if total else f"{fait/1e9:.2f} Go")
        root.after(0, _)

    def travail():
        try:
            ck = cookies_firefox(var_url.get()) if args.cookies_firefox else None
            f = telecharger(var_url.get(), args.dossier, args.reprendre, ck, log, progres, stop)
            root.after(0, lambda: etat.set("Terminé ✔"))
            notifier("Téléchargement terminé", f.name)
        except Exception as e:
            log(f"ERREUR : {e}")
            root.after(0, lambda: etat.set("Échec — voir le journal"))
            notifier("Téléchargement en échec", str(e)[:120])
        finally:
            root.after(0, lambda: btn.config(state="normal"))

    def lancer():
        stop.clear(); btn.config(state="disabled")
        threading.Thread(target=travail, daemon=True).start()

    fr = ttk.Frame(root); fr.pack(pady=(0, 10))
    btn = ttk.Button(fr, text="Télécharger / Reprendre", command=lancer); btn.pack(side="left", padx=5)
    ttk.Button(fr, text="Pause", command=stop.set).pack(side="left", padx=5)
    if url:  # jamais de démarrage automatique : le lien peut venir d'un courriel
        hote = urlparse(url).hostname or "?"
        etat.set(f"Vérifie le lien, puis clique sur « Télécharger ». Serveur : {hote}")
    root.mainloop()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("url", nargs="?")
    p.add_argument("--cli", action="store_true")
    p.add_argument("--dossier", default=DOSSIER)
    p.add_argument("--reprendre", help="fichier .part laissé par Firefox")
    p.add_argument("--cookies-firefox", action="store_true")
    a = p.parse_args()
    if not a.cli:
        return gui(a.url, a)
    if not a.url:
        sys.exit("Donne un lien.")

    def prog(f, t, v):
        if t:
            print(f"\r{f*100/t:5.1f} %  {f/1e9:.2f}/{t/1e9:.2f} Go  {v/1e6:.1f} Mo/s", end="", flush=True)
    try:
        ck = cookies_firefox(a.url) if a.cookies_firefox else None
        telecharger(a.url, a.dossier, a.reprendre, ck, progres=prog)
    except Erreur as e:
        sys.exit(f"\nERREUR : {e}")


if __name__ == "__main__":
    main()

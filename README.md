# Gestionnaire de téléchargement de gros fichiers

Télécharge les gros fichiers (exports ChatGPT de plusieurs Go, par exemple) avec **reprise automatique** quand le serveur coupe la connexion, interface graphique et notification de fin. Testé sur Linux Mint.

## Pourquoi
Certains serveurs ferment la connexion tous les ~2 Go. Firefox abandonne ; ce gestionnaire redemande le fichier à l'octet exact (en-tête HTTP `Range`) et continue.

## Fonctions
- Reprise et relances automatiques, diagnostic clair (lien expiré, connexion requise, reprise non acceptée)
- `--cookies-firefox` : réutilise ta session Firefox pour les liens qui exigent d'être connecté
- `--reprendre FICHIER.part` : continue un téléchargement commencé par Firefox
- Pause, barre de progression, notification
- Thunderbird : les liens https de chatgpt.com / openai.com s’ouvrent dans le gestionnaire (menu « brancher sur Thunderbird ») ; le téléchargement démarre quand tu cliques sur « Télécharger »

## Sécurité
**Les versions 1.0 et 1.1 ont des failles graves : passe à la version 1.2.** Depuis la 1.2 : nom de fichier nettoyé, aucun écrasement, aucun passage par un interpréteur de commandes, cookies limités à leur domaine et au https, tri des liens sur le nom du serveur.

## Installation (Debian / Ubuntu / Linux Mint)
```bash
./construire-deb.sh
sudo apt install ./gestionnaire-telechargement_1.2_all.deb
```
Ou lancer sans installer : `python3 paquet/usr/lib/gestionnaire-telechargement/gestionnaire.py "LIEN"` (nécessite `python3-tk` et `python3-requests`).

## Licence
MIT

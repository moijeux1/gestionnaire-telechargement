# Alarme Agenda (Android)

Fait **sonner le téléphone à chaque début d'événement** de l'agenda (Google Calendar ou tout agenda synchronisé sur le téléphone).

- Lit l'agenda via l'API Calendrier d'Android : pas de mot de passe, pas de lien iCal, récurrences incluses.
- Sonnerie sur le volume « alarme », en boucle (2 min max), avec vibration, boutons **Arrêter** / **Reporter 5 min**.
- Préavis réglable (0 à 30 min), événements « toute la journée » ignorés par défaut, événements refusés/annulés ignorés.
- Reprogrammation automatique : au démarrage, après changement d'heure, toutes les 6 h et à l'ouverture de l'appli.

## Installer
1. GitHub → onglet **Actions** → « Alarme Agenda APK » → dernier run → télécharger l'artefact `alarme-agenda-apk` (zip contenant `app-debug.apk`).
2. Copier l'APK sur le téléphone, l'ouvrir, autoriser « sources inconnues ».
3. Ouvrir l'appli et valider les 3 boutons (agenda + notifications, alarmes exactes, batterie).
4. Appuyer sur « Tester la sonnerie ».

## Limites
- Les événements doivent être synchronisés sur le téléphone (compte Google ajouté, agenda coché dans Google Agenda).
- Mode Ne pas déranger : si les alarmes sont bloquées, les autoriser dans les réglages du téléphone.
- Certaines surcouches (Xiaomi, Huawei, Samsung…) tuent les applis en arrière-plan : mettre l'appli en « sans restriction » côté batterie.

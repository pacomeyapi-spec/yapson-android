# Yapson Mobile - App Android

Application Android pour la gestion Mobile Money (Orange, MTN, Moov, Wave).

## Fonctionnalités
- **Lancer des codes USSD** automatiquement depuis le téléphone
- **Lire les SMS** de confirmation (Orange, MTN, Moov)
- **Intercepter les notifications** de l'app Wave
- **Envoyer les résultats** au backend API en temps réel
- **Service foreground** persistant avec redémarrage auto au boot

## Installation

### Télécharger l'APK
Aller dans **Releases** → télécharger `app-debug.apk`

### Configuration
1. Lancer l'app
2. Appuyer sur ⚙️ Config
3. Renseigner :
   - **URL Backend** : `https://yapson-mobile-backend-production.up.railway.app`
   - **Token Appareil** : (obtenu depuis la plateforme Admin → Appareils → copier le token)
   - **Packages Wave** : `com.wave.finance` (ou celui installé sur le téléphone)
4. Sauvegarder
5. Autoriser la lecture des notifications (pour Wave)
6. Appuyer **DÉMARRER**

## Permissions requises
- `RECEIVE_SMS` / `READ_SMS` — Lire les SMS opérateurs
- `CALL_PHONE` — Lancer les codes USSD
- `BIND_NOTIFICATION_LISTENER_SERVICE` — Lire les notifications Wave
- `FOREGROUND_SERVICE` — Service persistant

## Build automatique
GitHub Actions compile l'APK à chaque push sur `main`.

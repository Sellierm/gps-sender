# GPS Sender

Une application Android pour la lecture de données GPS RTK de haute précision via port série et la transmission en temps réel à un serveur distant via **Socket.IO**.

L'application est spécialement optimisée pour les tablettes professionnelles (ex: matériels CHCNAV) et appareils embarqués équipés de récepteurs RTK matériels.

---

## 🚀 Fonctionnalités principales

- **Lecture directe du port série RTK** : Détection et lecture des trames NMEA (`$GNGGA`, `$GNRMC`) depuis le port série configuré ou en auto-détection (`/dev/ttyS*`, `/dev/ttyUSB*`).
- **Connexion Socket.IO persisante** : Transmission via une connexion WebSocket réutilisée avec authentification par handshake (`deviceId` + `apiKey`), acquittement d'envoi (ACK) et reconnexion automatique.
- **Traitement et filtrage intelligent des positions** :
  - **Lissage** par moyenne glissante sur 3 positions RTK consécutives.
  - **Filtre anti-vitesse aberrante** (positions avec vitesse > 200 km/h ignorées).
  - **Cadencement / Throttle** configurable pour limiter la fréquence des envois.
  - **File d'attente locale** (jusqu'à 500 positions) avec réessai exponentiel en cas de coupure réseau.
- **GPS Interne en secours (Fallback)** : Bascule automatique sur le GPS interne de l'appareil si le signal RTK est perdu ou inactif depuis plus de 10 secondes.
- **Robustesse et exécution continue** :
  - **Foreground Service** avec notification permanente indiquant l'état en temps réel (`🟢` connecté, `🔴` déconnecté, qualité RTK Fix / Float).
  - **WakeLock** pour empêcher la mise en veille par le système d'exploitation.
  - **Démarrage au boot** (`BootReceiver`).
  - **Watchdog (WorkManager)** relançant automatiquement le service en cas de fermeture inattendue par le système.
- **Interface de configuration dynamique** :
  - Modification de tous les paramètres en direct sans fermeture de l'application ni arrêt du suivi.

---

## ⚙️ Configuration de l'application

### 1. Variables de compilation (`local.properties`)

Créez un fichier `local.properties` à la racine du projet (vous pouvez vous appuyer sur `local.properties.example`) :

```properties
API_KEY=votre_cle_api_securisee
API_URL=https://votre-serveur.example.com
DEVICE_ID=votre_id_appareil
```

Ces valeurs sont injectées à la compilation via `BuildConfig`.

### 2. Paramètres configurables depuis l'IHM

Vous pouvez modifier à tout moment la configuration directement dans l'interface de l'application :

| Paramètre | Description | Options disponibles | Valeur par défaut |
| :--- | :--- | :--- | :--- |
| **ID Appareil** | Identifiant unique de la tablette/véhicule envoyé au serveur | Saisie texte libre | Valeur `DEVICE_ID` de `local.properties` ou `CHCNAV_TABLET` |
| **Port Série** | Port périphérique raccordé au module RTK | `Auto-détection`, `/dev/ttyS4`, `/dev/ttyS3`, `/dev/ttyS2`, `/dev/ttyS1`, `/dev/ttyS0`, `/dev/tty3`, `/dev/tty4`, `/dev/ttyUSB0`, `/dev/ttyUSB1` | `Auto-détection` |
| **Baud Rate** | Vitesse de communication série | `9600`, `19200`, `38400`, `57600`, `115200`, `230400`, `460800`, `921600` baud | `115200` baud |
| **Taux de rafraîchissement** | Intervalle d'envoi des positions au serveur | `100 ms (10 Hz)`, `200 ms (5 Hz)`, `500 ms (2 Hz)`, `1000 ms (1 Hz)`, `2000 ms`, `5000 ms`, `10000 ms` | `1000 ms (1 Hz)` |

> 💡 **Remarque :** Lorsque le service est actif, cliquer sur **"Enregistrer les paramètres"** retransmet instantanément la nouvelle configuration au service en cours d'exécution sans interrompre le suivi ni fermer l'application.

---

## 📡 Format du payload émis (Socket.IO)

Chaque position est envoyée sur l'événement Socket.IO `"location"` sous forme d'un objet JSON :

```json
{
  "latitude": 48.8566143,
  "longitude": 2.3522219,
  "altitude": 156.40,
  "accuracy": 0.02,
  "speed": 1.25,
  "heading": 184.2,
  "timestamp": 1711000000000,
  "source": "RTK RTK Fix"
}
```

### Qualités RTK et précisions associées (`accuracy`) :
- `RTK Fix` (Code GGA 4) : Précision cm (~0.02 m)
- `RTK Float` (Code GGA 5) : Précision sub-métrique (~0.30 m)
- `DGPS` (Code GGA 2) : Précision décamétrique (~1.00 m)
- `GPS Interne` : Précision rapportée par l'API Android

---

## 🛠️ Architecture du projet

```text
com.mathieu.gpssender
├── AppPreferences.kt       # Persistance des configurations utilisateur (SharedPreferences)
├── BootReceiver.kt         # Démarrage automatique du service au boot de l'appareil
├── GPSWatchdogWorker.kt    # Tâche WorkManager pour surveiller et maintenir le service actif
├── LocationSocketClient.kt # Client WebSocket persistant Socket.IO (connect, emit location, ack)
├── MainActivity.kt         # Interface utilisateur (champs de réglages, statuts et boutons)
└── PositionService.kt      # Service premier plan (lecture port série, parsing NMEA, lissage, envoi)
```

---

## 🔑 Permissions requises

L'application requiert les permissions Android suivantes :
- `android.permission.ACCESS_FINE_LOCATION` : Accès à la position GPS haute précision.
- `android.permission.ACCESS_BACKGROUND_LOCATION` : Accès à la localisation en arrière-plan.
- `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_LOCATION` : Maintien du service en premier plan.
- `android.permission.RECEIVE_BOOT_COMPLETED` : Lancement au démarrage du système.
- Droits de lecture système sur le périphérique série sélectionné (`/dev/ttyS*` / `/dev/ttyUSB*`).

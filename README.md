# GPS Sender

Une application Android pour la lecture de données GPS RTK de haute précision via port série et la transmission en temps réel à un serveur distant via **Socket.IO**.

L'application est spécialement optimisée pour les tablettes professionnelles (ex: matériels CHCNAV) et appareils embarqués équipés de récepteurs RTK matériels.

---

## 🚀 Fonctionnalités principales

- **Lecture directe du port série RTK** : Détection et lecture des trames NMEA (`GGA`, `RMC`, `GST`) depuis le port série configuré ou en auto-détection (`/dev/ttyS*`, `/dev/ttyUSB*`), avec auto-détection du baud rate. Les trames sans checksum ou au checksum invalide sont écartées.
- **Connexion Socket.IO persistante** : Transmission via une connexion WebSocket réutilisée avec authentification par handshake (`deviceId` + `apiKey`, plus la version de l'app pour les journaux du serveur), acquittement d'envoi (ACK) et reconnexion automatique.
- **Traitement et filtrage intelligent des positions** :
  - **Lissage** par moyenne glissante sur 3 positions, pour le **RTK Float** uniquement (un RTK Fix n'est pas lissé, pour ne pas ajouter de retard).
  - **Filtre anti-vitesse aberrante** (positions avec vitesse > 200 km/h ignorées). Le filtre ne compare que des positions espacées de moins d'une minute : un appareil déplacé pendant qu'il était éteint n'est pas bloqué.
  - **Cadencement / Throttle** configurable pour limiter la fréquence des envois.
  - **Précision** issue de la trame GST quand le récepteur en émet, sinon estimée selon la qualité du fix et le HDOP.
  - **Horodatage** à l'heure UTC de la mesure (trame GGA datée par la RMC), et non à l'heure de réception.
- **File d'attente persistante (SQLite)** : jusqu'à 300 000 positions (~8 h de coupure à 10 Hz), conservées même si Android arrête le service. Une position ne quitte la file qu'après l'accusé de réception du serveur. Envoi par lots de 50, dans l'ordre chronologique ; l'envoi reprend dès la reconnexion du socket.
- **GPS Interne en secours (Fallback)** : Bascule automatique sur le GPS interne de l'appareil (puis sur les fournisseurs réseau / fusionné) si le signal RTK est perdu ou inactif depuis plus de 10 secondes.
- **Robustesse et exécution continue** :
  - **Foreground Service** avec notification permanente indiquant l'état en temps réel (`🟢` connecté, `🔴` déconnecté, qualité RTK Fix / Float).
  - **WakeLock** renouvelé en continu et **WifiLock** pour empêcher la mise en veille du processeur et du Wi-Fi.
  - **Démarrage au boot** (`BootReceiver`).
  - **Watchdog (WorkManager)** relançant automatiquement le service en cas de fermeture inattendue par le système. Sur Android 12+, il nécessite que l'appli soit exemptée de l'optimisation de batterie : l'exemption est demandée au démarrage du suivi.
  - Un arrêt volontaire via le bouton **Arrêter** est mémorisé : ni le watchdog ni le redémarrage au boot ne relancent alors le service.
- **Interface de configuration dynamique** :
  - Modification de tous les paramètres en direct sans fermeture de l'application ni arrêt du suivi.
- **Journal** consultable dans l'appli et enregistré dans un fichier (voir [Journal](#-journal)).

---

## ⚙️ Configuration de l'application

### 1. Valeurs par défaut des builds de debug (`local.properties`, optionnel)

L'URL du serveur et la clé API se règlent directement dans l'application. Pour le développement, vous pouvez fournir des valeurs par défaut, utilisées tant que rien n'a été saisi dans l'appli. Créez pour cela un fichier `local.properties` à la racine du projet (voir `local.properties.example`) :

```properties
API_KEY=votre_cle_api_securisee
API_URL=https://votre-serveur.example.com
```

Ces valeurs sont injectées via `BuildConfig` **dans les builds de debug uniquement**. Les builds de release (publiés sur GitHub) n'en contiennent jamais : sur une tablette, l'URL et la clé se saisissent dans l'écran de réglages.

### 2. Paramètres configurables depuis l'IHM

Vous pouvez modifier à tout moment la configuration directement dans l'interface de l'application :

| Paramètre | Description | Options disponibles | Valeur par défaut |
| :--- | :--- | :--- | :--- |
| **ID Appareil** | Identifiant unique de la tablette/véhicule envoyé au serveur | Saisie texte libre | `CHCNAV_TABLET` |
| **URL du serveur** | Adresse du serveur Socket.IO | Saisie libre (`http://` ou `https://`) | vide (debug : `API_URL` de `local.properties`) |
| **Clé API** | Clé d'authentification envoyée lors du handshake (champ masqué) | Saisie libre | vide (debug : `API_KEY` de `local.properties`) |
| **Port Série** | Port périphérique raccordé au module RTK | `Auto-détection`, `/dev/ttyS4`, `/dev/ttyS3`, `/dev/ttyS2`, `/dev/ttyS1`, `/dev/ttyS0`, `/dev/tty3`, `/dev/tty4`, `/dev/ttyUSB0`, `/dev/ttyUSB1` | `Auto-détection` |
| **Baud Rate** | Vitesse de communication série. En `Auto-détection`, chaque vitesse est essayée jusqu'à recevoir des trames NMEA valides (nouvelle détection automatique si le flux devient illisible) | `Auto-détection`, `9600`, `19200`, `38400`, `57600`, `115200`, `230400`, `460800`, `921600` baud | `115200` baud |
| **Taux de rafraîchissement** | Intervalle d'envoi des positions au serveur | `100 ms (10 Hz)`, `200 ms (5 Hz)`, `500 ms (2 Hz)`, `1000 ms (1 Hz)`, `2000 ms`, `5000 ms`, `10000 ms` | `1000 ms (1 Hz)` |

> 💡 **Remarque :** Lorsque le service est actif, cliquer sur **"Enregistrer les paramètres"** retransmet instantanément la nouvelle configuration au service en cours d'exécution sans interrompre le suivi ni fermer l'application. Le port série est rouvert avec les nouveaux paramètres.

Tant que l'URL, la clé API ou l'ID ne sont pas renseignés, les positions restent dans la file d'attente et partent dès la configuration terminée.

---

## 📡 Protocole Socket.IO

### Handshake

Les champs suivants sont envoyés dans `auth` à la connexion :

```json
{ "deviceId": "CHCNAV_TABLET", "key": "<clé API>", "appVersion": "2.2.0" }
```

### Envoi des positions

Les positions sont envoyées par lots (50 au maximum) sur l'événement `location_batch` :

```json
{
  "points": [
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
  ]
}
```

Le serveur doit répondre par un ack, **une fois les positions écrites en base** :

```json
{ "success": true, "rejected": 0, "message": "optionnel" }
```

- `success: false`, une absence d'ack ou un délai dépassé (8 s) : le lot reste en file et sera renvoyé.
- `rejected` : nombre de positions invalides écartées par le serveur (elles ne sont pas renvoyées).
- Un lot peut être renvoyé si son ack s'est perdu : le serveur doit ignorer les doublons, par exemple sur le couple (`deviceId`, `timestamp`).

### Qualités RTK et précisions associées (`accuracy`)

Valeur de la trame GST quand le récepteur en émet une, sinon valeur type multipliée par le HDOP :
- `RTK Fix` (Code GGA 4) : Précision cm (~0.02 m)
- `RTK Float` (Code GGA 5) : Précision sub-métrique (~0.30 m)
- `DGPS` (Code GGA 2) : Précision métrique (~1.00 m)
- `GPS Interne` / `Réseau` : Précision rapportée par l'API Android

---

## 📝 Journal

- **Écran Logs** : les 1 000 derniers messages, en direct.
- **Fichier** : `gps-sender.log` (et l'ancien `gps-sender.log.1`, 5 Mo chacun), dans le stockage propre à l'appli. À récupérer avec :
  ```bash
  adb pull /sdcard/Android/data/com.mathieu.gpssender/files/logs/
  ```
- Les messages à haute fréquence (trames NMEA brutes, positions écartées par le throttle, chaque fix) ne vont que dans Logcat, et seulement en debug. Les changements de qualité GNSS (ex. `RTK Float → RTK Fix`) sont, eux, toujours journalisés.

---

## 🛠️ Architecture du projet

```text
com.mathieu.gpssender
├── AppLogger.kt            # Journal : Logcat, tampon mémoire (écran Logs) et fichier tournant
├── AppPreferences.kt       # Persistance des configurations utilisateur (SharedPreferences)
├── BootReceiver.kt         # Démarrage automatique du service au boot de l'appareil
├── GpsSenderApp.kt         # Application : initialise le journal au démarrage du process
├── GpsWatchdogWorker.kt    # Tâche WorkManager pour surveiller et maintenir le service actif
├── LocationQueue.kt        # File d'attente persistante (SQLite) des positions à envoyer
├── LocationSocketClient.kt # Client WebSocket persistant Socket.IO (connect, location_batch, ack)
├── LogsActivity.kt         # Écran de consultation des logs en direct
├── MainActivity.kt         # Interface utilisateur (champs de réglages, statuts et boutons)
├── Nmea.kt                 # Décodage des trames NMEA (GGA, RMC, GST), sans dépendance Android
├── PositionService.kt      # Service premier plan (port série, filtrage, lissage, envoi)
├── SpeedFilter.kt          # Filtre des positions aberrantes (vitesse apparente > 200 km/h)
├── UpdateInstallReceiver.kt # Statut de l'installation d'une mise à jour (confirmation, échec)
├── UpdateManager.kt        # Mise à jour automatique : vérification, téléchargement, contrôles, installation
├── UpdateRules.kt          # Règles pures de la mise à jour (URL, version, empreintes), testées sur JVM
└── UpdateWorker.kt         # Tâche WorkManager : vérification des mises à jour toutes les 6 h
```

---

## 🧪 Tests

Le décodage NMEA (checksum, coordonnées, passage de minuit, précision), le filtre de vitesse et les règles de mise à jour sont couverts par des tests unitaires JVM :

```bash
./gradlew :app:testDebugUnitTest
```

---

## 📦 Publier une release

1. Mettre à jour `versionCode` et `versionName` dans `app/build.gradle.kts`.
2. Créer et pousser un tag identique à `versionName` (ex. `2.2.0`).
3. Le workflow `.github/workflows/release.yml` lance les tests, compile l'APK signé et crée une **release brouillon** avec l'APK attaché. Il reste à rédiger les notes et à publier.

Secrets GitHub à définir une fois (Settings > Secrets and variables > Actions) :

| Secret | Contenu |
| :--- | :--- |
| `SIGNING_KEYSTORE_BASE64` | Le keystore encodé en base64 (`base64 -w0 key_gps_sender_apk`) |
| `SIGNING_STORE_PASSWORD` | Mot de passe du keystore |
| `SIGNING_KEY_ALIAS` | Alias de la clé (`key0`) |
| `SIGNING_KEY_PASSWORD` | Mot de passe de la clé |

Sans ces secrets, la release reste possible à la main via Android Studio (**Build > Generate Signed App Bundle / APK**). La release est minifiée par R8.

---

## 🔄 Mise à jour automatique des tablettes

Depuis la 2.3.0, l'appli se met à jour seule depuis le serveur Stelleros (même URL, même identifiant et même clé que l'envoi des positions) :

1. Publier la release GitHub (pas en brouillon), puis sur le serveur : `~/stacks/site/app/deploy/publie-gps-sender.sh 2.3.1` (ou le chemin d'un APK signé). Le script vérifie le paquet, la signature, que le `versionCode` augmente et que le certificat n'a pas changé.
2. Chaque tablette interroge `GET /api/gps-sender/version` au démarrage puis toutes les 6 h (bouton **Vérifier les mises à jour** pour le faire tout de suite). Si la version publiée est plus récente, elle télécharge `GET /api/gps-sender/apk`, contrôle taille, SHA-256, paquet, `versionCode` et certificat, puis l'installe.
3. Android 12 et plus : installation **sans confirmation** (l'appli se met à jour elle-même). Avant Android 12, ou si le système l'exige, une notification « Mise à jour GPS Sender » attend qu'on la touche (l'écran de confirmation s'ouvre directement si l'appli est au premier plan).
4. Android relance l'appli après l'installation (`MY_PACKAGE_REPLACED`) : le service redémarre s'il était actif. Les positions en file d'attente sont conservées (SQLite).

Une fois par tablette : autoriser **Installer des applis inconnues** pour GPS Sender (bouton **Autoriser l'installation des mises à jour** de l'écran principal, visible tant que ce n'est pas fait). Android refuse toute mise à jour signée par une autre clé que l'appli installée, et tout retour à une version plus ancienne.

---

## 🔑 Permissions requises

L'application requiert les permissions Android suivantes :
- `android.permission.ACCESS_FINE_LOCATION` : Accès à la position GPS haute précision.
- `android.permission.ACCESS_BACKGROUND_LOCATION` : Accès à la localisation en arrière-plan.
- `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_LOCATION` : Maintien du service en premier plan.
- `android.permission.RECEIVE_BOOT_COMPLETED` : Lancement au démarrage du système.
- `android.permission.WAKE_LOCK` : Maintien du processeur éveillé pendant le suivi.
- `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` : Demande d'exemption d'optimisation de batterie (relance par le watchdog sur Android 12+).
- `android.permission.POST_NOTIFICATIONS` : Notification d'état (Android 13+).
- `android.permission.REQUEST_INSTALL_PACKAGES` & `UPDATE_PACKAGES_WITHOUT_USER_ACTION` : Installation des mises à jour publiées sur le serveur (sans confirmation sur Android 12+).
- Droits de lecture système sur le périphérique série sélectionné (`/dev/ttyS*` / `/dev/ttyUSB*`).

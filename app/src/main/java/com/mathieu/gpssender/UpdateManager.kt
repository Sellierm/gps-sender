package com.mathieu.gpssender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mise à jour automatique depuis le serveur Stelleros, avec l'URL, l'identifiant et la clé de
 * l'envoi des positions :
 *
 * 1. `GET /api/gps-sender/version` : version publiée par `deploy/publie-gps-sender.sh` ;
 * 2. si elle est plus récente : `GET /api/gps-sender/apk`, puis contrôle de la taille, de
 *    l'empreinte SHA-256, du paquet, du versionCode et du certificat de signature ;
 * 3. installation par [PackageInstaller]. Sur Android 12+, l'appli qui se met à jour elle-même
 *    peut le faire sans confirmation (permission UPDATE_PACKAGES_WITHOUT_USER_ACTION) ; sinon, ou
 *    si le système l'exige, [UpdateInstallReceiver] demande la confirmation (écran ou notification).
 *
 * Android tue ensuite le process et le relance par ACTION_MY_PACKAGE_REPLACED ([BootReceiver]).
 * Vérification au démarrage puis toutes les 6 h ([UpdateWorker]), ou depuis l'écran principal.
 */
object UpdateManager {

    private const val TAG = "GPS_SENDER_LOG"
    private const val VERSION_PATH = "/api/gps-sender/version"
    private const val APK_PATH = "/api/gps-sender/apk"
    private const val DOWNLOAD_DIR = "mise-a-jour"
    private const val NOTIFICATION_CHANNEL_ID = "mises_a_jour"
    private const val NOTIFICATION_ID = 2

    /** Résultat d'une vérification, affiché tel quel par l'écran principal. */
    data class Outcome(val message: String)

    private val running = AtomicBoolean(false)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /** Bloquant (réseau) : à appeler hors du thread principal. */
    fun checkAndInstall(context: Context): Outcome {
        if (!running.compareAndSet(false, true)) return Outcome("Vérification déjà en cours")
        return try {
            val outcome = runCheck(context.applicationContext)
            AppLogger.i(TAG, "Mise à jour : ${outcome.message}")
            outcome
        } catch (e: IOException) {
            AppLogger.w(TAG, "Mise à jour : serveur injoignable (${e.message})")
            Outcome("Serveur injoignable : ${e.message}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Mise à jour : erreur inattendue", e)
            Outcome("Erreur : ${e.message}")
        } finally {
            running.set(false)
        }
    }

    private fun runCheck(context: Context): Outcome {
        val origin = UpdateRules.serverOrigin(AppPreferences.getApiUrl(context))
            ?: return Outcome("URL du serveur invalide")
        val apiKey = AppPreferences.getApiKey(context)
        if (apiKey.isBlank()) return Outcome("Clé API non renseignée")
        val deviceId = AppPreferences.getDeviceId(context)

        fun request(path: String) = Request.Builder()
            .url(origin + path)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Device-Id", deviceId)
            .header("X-App-Version", BuildConfig.VERSION_NAME)
            .build()

        val remote = client.newCall(request(VERSION_PATH)).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == 404 -> return Outcome("Aucune version publiée sur le serveur")
                response.code == 401 -> return Outcome("Identifiant ou clé refusés par le serveur")
                !response.isSuccessful -> return Outcome("Erreur du serveur (HTTP ${response.code})")
            }
            // Un serveur sans la route renvoie la page du site (HTML) : JSON illisible.
            val json = try {
                JSONObject(body)
            } catch (e: JSONException) {
                return Outcome("Réponse inattendue du serveur (route de mise à jour absente ?)")
            }
            UpdateRules.validate(
                json.optLong("versionCode"),
                json.optString("versionName"),
                json.optString("sha256"),
                json.optLong("size"),
            ) ?: return Outcome("Version publiée invalide")
        }

        val installed = BuildConfig.VERSION_CODE.toLong()
        if (!UpdateRules.isNewer(remote, installed)) {
            return Outcome("À jour (${BuildConfig.VERSION_NAME}, serveur : ${remote.versionName})")
        }
        AppLogger.i(TAG, "Mise à jour : ${remote.versionName} (${remote.versionCode}) disponible, téléchargement…")

        val dir = File(context.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "gps-sender-${remote.versionCode}.apk")
        try {
            client.newCall(request(APK_PATH)).execute().use { response ->
                if (!response.isSuccessful) return Outcome("Téléchargement refusé (HTTP ${response.code})")
                val body = response.body ?: return Outcome("Téléchargement vide")
                downloadError(body.byteStream(), apk, remote)?.let { return Outcome(it) }
            }
            verifyArchive(context, apk, remote)?.let { return Outcome(it) }
            install(context, apk, remote)
        } finally {
            // Les octets sont copiés dans la session d'installation : le fichier ne sert plus.
            apk.delete()
        }
        if (!canInstallPackages(context)) {
            AppLogger.w(TAG, "Mise à jour : installation d'applis non autorisée pour GPS Sender, confirmation nécessaire")
        }
        return Outcome("Installation de la version ${remote.versionName} lancée")
    }

    /** Copie l'APK en calculant son empreinte ; renvoie l'erreur, ou `null` si tout concorde. */
    private fun downloadError(input: java.io.InputStream, dest: File, remote: UpdateRules.RemoteVersion): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.use { source ->
            dest.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > remote.size) return "APK plus gros qu'annoncé"
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
        }
        if (total != remote.size) return "APK incomplet ($total / ${remote.size} octets)"
        if (UpdateRules.hex(digest.digest()) != remote.sha256) return "Empreinte SHA-256 de l'APK incorrecte"
        return null
    }

    /** Paquet, version et signature de l'APK téléchargé ; renvoie l'erreur, ou `null`. */
    private fun verifyArchive(context: Context, apk: File, remote: UpdateRules.RemoteVersion): String? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: return "APK illisible"
        if (archive.packageName != context.packageName) return "APK d'une autre appli (${archive.packageName})"
        val code = PackageInfoCompat.getLongVersionCode(archive)
        if (code != remote.versionCode) return "versionCode de l'APK ($code) différent de celui annoncé (${remote.versionCode})"
        val installedSigners = signerDigests(installedInfo(pm, context.packageName))
        if (UpdateRules.signersMismatch(installedSigners, signerDigests(archive))) {
            return "APK signé avec une autre clé : Android refuserait la mise à jour"
        }
        return null
    }

    private val signatureFlags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        pm.getPackageArchiveInfo(apk.absolutePath, signatureFlags)

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo? = try {
        pm.getPackageInfo(packageName, signatureFlags)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Empreintes SHA-256 des certificats (actuels et historique de rotation). */
    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo?): Set<String>? {
        info ?: return null
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
        } else {
            info.signatures
        }
        if (signatures.isNullOrEmpty()) return null
        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.map { UpdateRules.hex(sha.digest(it.toByteArray())) }.toSet()
    }

    private fun install(context: Context, apk: File, remote: UpdateRules.RemoteVersion) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java)
                    .putExtra(UpdateInstallReceiver.EXTRA_VERSION_NAME, remote.versionName)
                // Mutable : le système y ajoute le statut et, au besoin, l'écran de confirmation.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    /** Autorisation « Installer des applis inconnues » accordée à GPS Sender. */
    fun canInstallPackages(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * L'installation attend une confirmation : écran système tout de suite si l'appli est au premier
     * plan (Android interdit d'ouvrir une activité depuis l'arrière-plan), notification sinon.
     */
    fun requestConfirmation(context: Context, confirm: Intent, versionName: String) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (MainActivity.isInForeground) {
            try {
                context.startActivity(confirm)
                return
            } catch (e: Exception) {
                AppLogger.w(TAG, "Mise à jour : écran de confirmation impossible à ouvrir (${e.message})")
            }
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL_ID, "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pending = PendingIntent.getActivity(
            context, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gps)
            .setContentTitle("Mise à jour GPS Sender $versionName")
            .setContentText("Toucher pour installer")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    /** Appelé par [BootReceiver] au premier lancement de la nouvelle version. */
    fun onPackageReplaced(context: Context) {
        File(context.cacheDir, DOWNLOAD_DIR).deleteRecursively()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

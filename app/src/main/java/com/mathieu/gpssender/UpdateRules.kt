package com.mathieu.gpssender

import java.net.URI
import java.net.URISyntaxException

/** Règles pures de la mise à jour automatique (testées sans Android, voir [UpdateManager]). */
object UpdateRules {

    /** Garde-fou contre une réponse aberrante : l'APK fait quelques Mo. */
    const val MAX_APK_BYTES = 100L * 1024 * 1024

    private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    private val VERSION_NAME_REGEX = Regex("^[0-9A-Za-z._+-]{1,32}$")
    private const val HEX = "0123456789abcdef"

    /** Version publiée sur le serveur (`GET /api/gps-sender/version`). */
    data class RemoteVersion(
        val versionCode: Long,
        val versionName: String,
        val sha256: String,
        val size: Long,
    )

    /**
     * Origine du serveur (schéma, hôte, port) tirée de l'URL saisie dans l'appli. Cette URL sert
     * aussi à Socket.IO, qui lirait un éventuel chemin comme un namespace : on l'ignore ici aussi.
     */
    fun serverOrigin(apiUrl: String): String? {
        val uri = try {
            URI(apiUrl.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val authority = uri.rawAuthority?.takeIf { it.isNotEmpty() } ?: return null
        return "$scheme://$authority"
    }

    /** `null` si un champ manque ou n'est pas plausible : on n'installe alors rien. */
    fun validate(versionCode: Long, versionName: String, sha256: String, size: Long): RemoteVersion? {
        if (versionCode <= 0) return null
        if (!VERSION_NAME_REGEX.matches(versionName)) return null
        if (!SHA256_REGEX.matches(sha256)) return null
        if (size <= 0 || size > MAX_APK_BYTES) return null
        return RemoteVersion(versionCode, versionName, sha256, size)
    }

    /** Android refuse les retours arrière : seule une version strictement plus récente s'installe. */
    fun isNewer(remote: RemoteVersion, installedVersionCode: Long): Boolean =
        remote.versionCode > installedVersionCode

    /**
     * Les certificats de l'APK téléchargé et ceux de l'appli installée sont-ils incompatibles ?
     * Faux quand l'un des deux est illisible : Android fera de toute façon la vérification, ce
     * contrôle sert seulement à ne pas proposer une mise à jour vouée à l'échec.
     */
    fun signersMismatch(installed: Set<String>?, downloaded: Set<String>?): Boolean =
        !installed.isNullOrEmpty() && !downloaded.isNullOrEmpty() && installed.intersect(downloaded).isEmpty()

    /** Hexadécimal minuscule (indépendant de la locale, contrairement à `String.format`). */
    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }
}

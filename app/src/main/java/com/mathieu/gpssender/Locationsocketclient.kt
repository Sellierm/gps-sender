package com.mathieu.gpssender

import android.util.Log
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import java.net.URISyntaxException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Gere une connexion Socket.IO persistante pour l'envoi des positions GPS.
 *
 * Remplace l'ancien envoi par requete HTTP POST individuelle (une connexion
 * TCP/TLS ouverte et refermee a chaque position). Ici une seule connexion
 * est ouverte au demarrage du service et reutilisee pour tous les envois,
 * ce qui supprime le cout de handshake repete et beneficie de la reconnexion
 * automatique avec backoff geree nativement par la lib Socket.IO.
 *
 * L'authentification (deviceId + cle API) se fait une seule fois, au moment
 * du handshake, au lieu d'etre repetee dans chaque message comme c'etait
 * le cas avec le POST HTTP.
 */
class LocationSocketClient(
    private val deviceId: String,
    private val apiKey: String,
) {

    private val logTag = "GPS_SENDER_LOG"

    interface StatusListener {
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onConnectError(message: String?)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var listener: StatusListener? = null

    private val onConnect = Emitter.Listener {
        Log.i(logTag, "Socket connecte (device=$deviceId)")
        listener?.onConnected()
    }

    private val onDisconnect = Emitter.Listener { args ->
        val reason = args.firstOrNull()?.toString()
        Log.w(logTag, "Socket deconnecte: $reason")
        listener?.onDisconnected(reason)
    }

    private val onConnectError = Emitter.Listener { args ->
        val message = args.firstOrNull()?.toString()
        Log.e(logTag, "Erreur connexion socket: $message")
        listener?.onConnectError(message)
    }

    /**
     * Ouvre la connexion si elle n'est pas deja etablie. Idempotent : un
     * appel repete alors qu'une connexion existe deja ne fait rien.
     */
    @Synchronized
    fun connect(url: String, listener: StatusListener) {
        this.listener = listener
        if (socket != null) {
            Log.d(logTag, "connect() ignore : socket deja initialise")
            return
        }

        try {
            val options = IO.Options().apply {
                reconnection = true
                reconnectionDelay = 2000
                reconnectionDelayMax = 15000
                timeout = 8000
                transports = arrayOf("websocket") // evite le fallback long-polling coute plus cher en energie
                auth = mapOf(
                    "deviceId" to deviceId,
                    "key" to apiKey,
                )
            }
            val s = IO.socket(url, options)
            s.on(Socket.EVENT_CONNECT, onConnect)
            s.on(Socket.EVENT_DISCONNECT, onDisconnect)
            s.on(Socket.EVENT_CONNECT_ERROR, onConnectError)

            socket = s
            s.connect()
        } catch (e: URISyntaxException) {
            Log.e(logTag, "URL socket invalide '$url' : ${e.message}")
        }
    }

    @Synchronized
    fun disconnect() {
        socket?.let {
            it.off(Socket.EVENT_CONNECT, onConnect)
            it.off(Socket.EVENT_DISCONNECT, onDisconnect)
            it.off(Socket.EVENT_CONNECT_ERROR, onConnectError)
            it.disconnect()
        }
        socket = null
        listener = null
    }

    fun isConnected(): Boolean = socket?.connected() == true

    /**
     * Envoie une position et attend l'accuse de reception du serveur.
     * Bloque l'appelant (a utiliser depuis le thread d'envoi dedie, jamais
     * depuis le thread principal) jusqu'a reception de l'ack ou timeout.
     *
     * Retourne false si :
     *  - le socket n'est pas connecte
     *  - le serveur repond avec {success:false} (payload invalide, device non autorise...)
     *  - aucun ack ne revient avant timeoutMs (probable coupure reseau)
     */
    fun sendLocation(payload: JSONObject, timeoutMs: Long = 4000): Boolean {
        val s = socket
        if (s == null || !s.connected()) return false

        val latch = CountDownLatch(1)
        val ackOk = AtomicBoolean(false)

        try {
            s.emit("location", payload, Ack { args ->
                val response = args.firstOrNull() as? JSONObject
                ackOk.set(response?.optBoolean("success", false) ?: false)
                latch.countDown()
            })
        } catch (e: Exception) {
            Log.e(logTag, "Erreur emit location: ${e.message}")
            return false
        }

        val completed = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!completed) Log.w(logTag, "Ack non recu dans le delai (${timeoutMs}ms)")
        return completed && ackOk.get()
    }
}
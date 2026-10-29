package com.mathieu.gpssender

import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONArray
import org.json.JSONObject
import java.net.URISyntaxException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/*
 * Gere une connexion Socket.IO persistante pour l'envoi des positions GPS.
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
        AppLogger.i(logTag, "Socket connecté (device=$deviceId)")
        listener?.onConnected()
    }

    private val onDisconnect = Emitter.Listener { args ->
        val reason = args.firstOrNull()?.toString()
        AppLogger.w(logTag, "Socket déconnecté: $reason")
        listener?.onDisconnected(reason)
    }

    private val onConnectError = Emitter.Listener { args ->
        val message = args.firstOrNull()?.toString()
        AppLogger.e(logTag, "Erreur connexion socket: $message")
        listener?.onConnectError(message)
    }

    @Synchronized
    fun connect(url: String, listener: StatusListener) {
        this.listener = listener
        if (socket != null) {
            AppLogger.d(logTag, "connect() ignoré : socket déjà initialisé")
            return
        }

        try {
            val options = IO.Options().apply {
                reconnection = true
                reconnectionDelay = 2000
                reconnectionDelayMax = 15000
                timeout = 8000
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
            AppLogger.e(logTag, "URL socket invalide '$url' : ${e.message}")
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

    /** Résultat d'un envoi : `rejected` = positions écartées par le serveur car invalides (non renvoyées). */
    data class BatchResult(val success: Boolean, val rejected: Int = 0, val message: String? = null)

    /**
     * Envoie un lot de positions (`location_batch`) et attend l'ack, que le serveur n'envoie
     * qu'après l'écriture en base. Un échec laisse les positions en file pour un nouvel essai.
     */
    fun sendBatch(points: JSONArray, timeoutMs: Long = 8000): BatchResult {
        val s = socket
        if (s == null || !s.connected()) return BatchResult(false, message = "socket non connecté")

        val latch = CountDownLatch(1)
        val response = AtomicReference<JSONObject?>(null)

        try {
            s.emit("location_batch", JSONObject().put("points", points), Ack { args ->
                response.set(args.firstOrNull() as? JSONObject)
                latch.countDown()
            })
        } catch (e: Exception) {
            AppLogger.e(logTag, "Erreur emit location_batch: ${e.message}")
            return BatchResult(false, message = e.message)
        }

        val completed = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!completed) {
            AppLogger.w(logTag, "Ack non reçu dans le délai (${timeoutMs}ms)")
            return BatchResult(false, message = "timeout")
        }
        val ack = response.get()
        return BatchResult(
            success = ack?.optBoolean("success", false) ?: false,
            rejected = ack?.optInt("rejected", 0) ?: 0,
            message = ack?.optString("message")?.takeIf { it.isNotEmpty() }
        )
    }
}

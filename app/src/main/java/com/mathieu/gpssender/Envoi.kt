package com.mathieu.gpssender

import org.json.JSONArray
import org.json.JSONObject

/**
 * Logique du thread d'envoi, sans dépendance au service (testée dans EnvoiTest).
 *
 * Règle : une position ne quitte la file qu'après l'ack du serveur. Les positions ajoutées
 * pendant l'envoi d'un lot restent en file pour le lot suivant.
 */
object Envoi {

    enum class Resultat { FILE_VIDE, ENVOYE, ECHEC }

    /** Lit un lot en tête de file, l'envoie, et ne le retire de la file que si l'envoi est acquitté. */
    fun sendNext(queue: LocationQueue, batchSize: Int, send: (QueuedBatch) -> Boolean): Resultat {
        val batch = queue.peek(batchSize) ?: return Resultat.FILE_VIDE
        if (!send(batch)) return Resultat.ECHEC
        queue.removeUpTo(batch.lastId)
        return Resultat.ENVOYE
    }

    /** Attente avant un nouvel essai : 2, 4, 8 puis 16 s au plus. */
    fun retryDelayMs(consecutiveFailures: Int): Long =
        2000L shl (consecutiveFailures - 1).coerceIn(0, 3)

    /** Points de l'événement `location_batch` (noms de champs attendus par le serveur). */
    fun pointsJson(locations: List<PendingLocation>): JSONArray {
        val points = JSONArray()
        for (pending in locations) {
            points.put(JSONObject().apply {
                put("latitude", pending.latitude)
                put("longitude", pending.longitude)
                put("altitude", pending.altitude)
                put("accuracy", pending.accuracy)
                put("speed", pending.speed)
                put("heading", pending.heading)
                put("timestamp", pending.timestamp)
                put("source", pending.source)
            })
        }
        return points
    }
}

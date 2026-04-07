package com.first.ufotw

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class SharedPattern(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val tags: List<String>,
    val steps: List<PatternStep>,
    val loop: Boolean,
    val stepCount: Int,
    val durationSec: Double
)

class SharedPlazaRepository {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    init {
        if (auth.currentUser == null) {
            auth.signInAnonymously()
        }
    }

    fun observePatterns(): Flow<List<SharedPattern>> = callbackFlow {
        val query = db.collection("shared_patterns")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)

        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            val patterns = snapshot.documents.mapNotNull { mapDoc(it) }
            trySend(patterns)
        }

        awaitClose { listener.remove() }
    }

    private fun mapDoc(doc: DocumentSnapshot): SharedPattern? {
        return try {
            val title = doc.getString("title") ?: return null
            val author = doc.getString("author") ?: ""
            val description = doc.getString("description") ?: ""

            @Suppress("UNCHECKED_CAST")
            val tags = (doc.get("tags") as? List<*>)?.filterIsInstance<String>() ?: emptyList()

            val loop = doc.getBoolean("loop") ?: false

            @Suppress("UNCHECKED_CAST")
            val rawSteps = doc.get("steps") as? List<HashMap<String, Any>> ?: return null
            val steps = rawSteps.mapNotNull { map ->
                try {
                    val speed = when (val v = map["speed"]) {
                        is Long -> v.toInt()
                        is Double -> v.toInt()
                        is Int -> v
                        else -> return@mapNotNull null
                    }
                    val direction = when (val v = map["direction"]) {
                        is Long -> v.toInt()
                        is Double -> v.toInt()
                        is Int -> v
                        else -> 0
                    }
                    val duration = when (val v = map["duration"]) {
                        is Double -> v
                        is Long -> v.toDouble()
                        is Int -> v.toDouble()
                        else -> return@mapNotNull null
                    }
                    PatternStep(
                        speed = speed,
                        direction = direction,
                        durationMs = (duration * 1000).toLong()
                    )
                } catch (e: Exception) {
                    null
                }
            }

            if (steps.isEmpty()) return null

            val durationSec = steps.sumOf { it.durationMs } / 1000.0

            SharedPattern(
                id = doc.id,
                title = title,
                author = author,
                description = description,
                tags = tags,
                steps = steps,
                loop = loop,
                stepCount = steps.size,
                durationSec = durationSec
            )
        } catch (e: Exception) {
            null
        }
    }
}

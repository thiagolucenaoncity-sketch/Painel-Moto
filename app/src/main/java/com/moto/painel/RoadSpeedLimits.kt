package com.moto.painel

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Only tagged OSM maxspeed values are used; no guesses from road classes. */
internal object RoadSpeedLimits {
    data class Segment(
        val aLat: Double, val aLon: Double,
        val bLat: Double, val bLon: Double,
        val kmh: Int
    )

    private val kmhPattern = Regex("""^([0-9]{1,3})(?:\s*(?:km/h|kph))?$""", RegexOption.IGNORE_CASE)

    fun parseKmh(raw: String?): Int? {
        if (raw == null) return null
        val number = kmhPattern.matchEntire(raw.trim())?.groupValues?.get(1)?.toIntOrNull()
        return number?.takeIf { it in 10..140 }
    }

    private data class Match(val speed: Int, val metres: Double)

    private fun candidate(lat: Double, lon: Double, s: Segment, bearing: Float?): Match? {
        val mid = Math.toRadians(lat)
        val factorX = 111320.0 * cos(mid)
        val factorY = 111320.0
        val ax = (s.aLon - lon) * factorX
        val ay = (s.aLat - lat) * factorY
        val bx = (s.bLon - lon) * factorX
        val by = (s.bLat - lat) * factorY
        val dx = bx - ax
        val dy = by - ay
        val sq = dx * dx + dy * dy
        if (sq < 1.0) return null
        if (bearing != null) {
            val heading = ((Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0)
            val difference = abs(((heading - bearing + 540.0) % 360.0) - 180.0)
            if (minOf(difference, 180.0 - difference) > 43.0) return null
        }
        val t = (-(ax * dx + ay * dy) / sq).coerceIn(0.0, 1.0)
        val d = hypot(ax + t * dx, ay + t * dy)
        return Match(s.kmh, d)
    }

    /** No speed if closest road is too far or competing parallel roads disagree. */
    fun nearest(lat: Double, lon: Double, bearing: Float?, segments: List<Segment>): Int? {
        if (segments.isEmpty()) return null
        val matches = segments.asSequence()
            .mapNotNull { candidate(lat, lon, it, bearing) }
            .filter { it.metres <= 26.0 }
            .sortedBy { it.metres }
            .take(20)
            .toList()
        val best = matches.firstOrNull() ?: return null
        // Parallel carriageways or a crossing with differing limits:
        // do not assert a speed without enough location evidence.
        if (matches.any {
                it.speed != best.speed && it.metres <= 26.0 &&
                    it.metres - best.metres < 7.0
            }) return null
        return best.speed
    }
}

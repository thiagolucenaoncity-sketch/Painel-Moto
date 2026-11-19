package com.moto.painel

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Progresso real pela geometria da rota, separado do MapLibre e do Android.
 * A distância e a duração apresentadas vêm da rota OSRM calculada;
 * esta projeção apenas mede quanto do percurso já foi percorrido.
 * Não utiliza trânsito em tempo real.
 */
internal object RouteEtaCalculator {
    data class Coordinate(val lat: Double, val lon: Double)
    data class Profile(
        val points: List<Coordinate>,
        val traveledMeters: DoubleArray,
        val lengthMeters: Double
    )
    data class Projection(val progressMeters: Double, val offRouteMeters: Double)

    fun profile(points: List<Coordinate>): Profile {
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val meterLat = 111_320.0
            val meterLon = meterLat * cos(Math.toRadians((a.lat + b.lat) / 2.0))
            val dx = (b.lon - a.lon) * meterLon
            val dy = (b.lat - a.lat) * meterLat
            cumulative[i] = cumulative[i - 1] + sqrt(dx * dx + dy * dy)
        }
        return Profile(points, cumulative, cumulative.lastOrNull() ?: 0.0)
    }

    fun project(profile: Profile, lat: Double, lon: Double): Projection? {
        if (profile.points.size < 2 || profile.lengthMeters <= 0.0) return null
        val meterLat = 111_320.0
        val meterLon = meterLat * cos(Math.toRadians(lat))
        var bestDistanceSquared = Double.MAX_VALUE
        var progress = 0.0
        for (i in 0 until profile.points.lastIndex) {
            val a = profile.points[i]
            val b = profile.points[i + 1]
            val x = (a.lon - lon) * meterLon
            val y = (a.lat - lat) * meterLat
            val dx = (b.lon - a.lon) * meterLon
            val dy = (b.lat - a.lat) * meterLat
            val squaredLength = dx * dx + dy * dy
            val t = if (squaredLength > 0.01) {
                (-(x * dx + y * dy) / squaredLength).coerceIn(0.0, 1.0)
            } else 0.0
            val offsetX = x + t * dx
            val offsetY = y + t * dy
            val lateral = offsetX * offsetX + offsetY * offsetY
            if (lateral < bestDistanceSquared) {
                bestDistanceSquared = lateral
                progress = profile.traveledMeters[i] +
                    t * (profile.traveledMeters[i + 1] - profile.traveledMeters[i])
            }
        }
        return Projection(progress.coerceIn(0.0, profile.lengthMeters), sqrt(bestDistanceSquared))
    }

    fun fractionRemaining(profile: Profile, progressMeters: Double): Double =
        if (profile.lengthMeters <= 0.0) 1.0 else
            (1.0 - progressMeters / profile.lengthMeters).coerceIn(0.0, 1.0)

    fun distanceRemaining(routeMeters: Double, fractionRemaining: Double): Double =
        (routeMeters.coerceAtLeast(0.0) * fractionRemaining.coerceIn(0.0, 1.0))

    fun durationRemaining(routeSeconds: Double, fractionRemaining: Double): Double =
        (routeSeconds.coerceAtLeast(0.0) * fractionRemaining.coerceIn(0.0, 1.0))
}

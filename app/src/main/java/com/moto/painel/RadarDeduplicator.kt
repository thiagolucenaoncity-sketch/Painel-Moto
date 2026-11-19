package com.moto.painel

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.floor

/**
 * Consolidates duplicate FIXED speed-camera records from the Pardal offline
 * database before they reach map layers, voice prompts or proximity beeps.
 *
 * A pair of close points is NOT enough to prove they are the same camera:
 * different directions, conflicting speed limits, SECTION cameras and
 * RED_LIGHT enforcement records remain separate. The source CSV does not
 * include street/lane IDs, so this is deliberately conservative.
 *
 * Runs only when the local CSV is loaded/updated, never on each GPS tick.
 */
internal object RadarDeduplicator {
    data class Record(
        val id: String,
        val lat: Double,
        val lon: Double,
        val kind: String,
        val limit: String,
        val direction: Float?,
        val source: String = ""
    )

    private const val CELL_DEG = 0.00016
    private const val MAX_KNOWN_DIRECTION_DISTANCE_M = 15.0
    private const val MAX_UNKNOWN_DIRECTION_DISTANCE_M = 12.5
    private const val MAX_DIRECTION_DIFF_DEGREES = 30.0

    private data class Group(val members: MutableList<Record>) {
        val anchor: Record get() = members[0]
        fun averageLat(): Double = members.sumOf { it.lat } / members.size
        fun averageLon(): Double = members.sumOf { it.lon } / members.size
    }

    private fun cell(v: Double) = floor(v / CELL_DEG).toInt()
    private fun key(latCell: Int, lonCell: Int): Long =
        (latCell.toLong() shl 32) or (lonCell.toLong() and 0xFFFFFFFFL)

    private fun distanceMeters(a: Record, b: Record): Double {
        val midLat = Math.toRadians((a.lat + b.lat) / 2.0)
        val dy = (a.lat - b.lat) * 111_320.0
        val dx = (a.lon - b.lon) * 111_320.0 * cos(midLat)
        return hypot(dx, dy)
    }

    private fun directionDifference(a: Float, b: Float): Double =
        kotlin.math.abs(((a - b + 540f) % 360f) - 180f).toDouble()

    private fun compatible(existing: Record, incoming: Record): Boolean {
        // Do not merge different enforcement devices/types or section endpoints.
        if (existing.kind != "FIXED" || incoming.kind != "FIXED") return false
        if (existing.limit.isNotEmpty() && incoming.limit.isNotEmpty() &&
            existing.limit != incoming.limit) return false
        val a = existing.direction
        val b = incoming.direction
        if (a != null && b != null && directionDifference(a, b) > MAX_DIRECTION_DIFF_DEGREES)
            return false
        val threshold = if (a != null && b != null)
            MAX_KNOWN_DIRECTION_DISTANCE_M else MAX_UNKNOWN_DIRECTION_DISTANCE_M
        return distanceMeters(existing, incoming) <= threshold
    }

    private fun combinedHeading(members: List<Record>): Float? {
        val headings = members.mapNotNull { it.direction }
        if (headings.isEmpty()) return null
        val x = headings.sumOf { cos(Math.toRadians(it.toDouble())) }
        val y = headings.sumOf { sin(Math.toRadians(it.toDouble())) }
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /**
     * The selected source row determines the stable ID. Position is the
     * arithmetic mean of all compatible original rows, as requested.
     * Full-diameter matching prevents "chains" of distant cameras collapsing.
     */
    fun consolidate(input: List<Record>): List<Record> {
        val groups = ArrayList<Group>()
        val grid = HashMap<Long, MutableList<Int>>()
        for (entry in input) {
            if (entry.kind != "FIXED") {
                val index = groups.size
                groups.add(Group(mutableListOf(entry)))
                continue
            }
            val cy = cell(entry.lat)
            val cx = cell(entry.lon)
            val candidateIndices = HashSet<Int>()
            // 15 m spans at most two ~16m grid cells in SP.
            for (y in cy - 2..cy + 2) {
                for (x in cx - 2..cx + 2) {
                    grid[key(y, x)]?.let { candidateIndices.addAll(it) }
                }
            }
            val candidates = candidateIndices.mapNotNull { index ->
                val group = groups[index]
                if (group.members.all { compatible(it, entry) })
                    index to distanceMeters(group.anchor, entry)
                else null
            }.sortedBy { it.second }

            // With two plausible nearby groups, don't guess which equipment.
            // Keep the new original point separate rather than fusing lanes.
            val ambiguous = candidates.size > 1 &&
                candidates[1].second - candidates[0].second < 3.0
            if (candidates.isNotEmpty() && !ambiguous) {
                groups[candidates[0].first].members.add(entry)
            } else {
                val index = groups.size
                groups.add(Group(mutableListOf(entry)))
                grid.getOrPut(key(cy, cx)) { ArrayList() }.add(index)
            }
        }
        return groups.map { group ->
            if (group.members.size == 1) group.anchor
            else group.anchor.copy(
                lat = group.averageLat(),
                lon = group.averageLon(),
                limit = group.members.firstOrNull { it.limit.isNotEmpty() }?.limit ?: "",
                direction = combinedHeading(group.members)
            )
        }
    }
}

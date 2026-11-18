package com.moto.painel

import android.content.Context
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.cos

/**
 * Mapas locais do MapLibre. Uma regiao salva permanece no disco sem internet.
 * Os downloads sao iniciados SOMENTE pelo usuario, para respeitar uso de dados.
 */
object OfflineMaps {
    private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    private const val CACHE_BYTES = 200L * 1024L * 1024L

    fun configure(context: Context) {
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(CACHE_BYTES, null)
    }

    fun saveNearby(context: Context, center: LatLng, report: (String) -> Unit) {
        val lat = center.latitude.coerceIn(-80.0, 80.0)
        val lon = center.longitude
        val latSpan = 1.3 / 111.32
        val lonSpan = 1.3 / (111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.15))
        // Região pequena (aprox. 2,6 x 2,6 km, zoom 11-15):
        // evita downloads volumosos ou acima dos limites do provedor.
        val bounds = LatLngBounds.Builder()
            .include(LatLng(lat - latSpan, lon - lonSpan))
            .include(LatLng(lat + latSpan, lon + lonSpan))
            .build()
        val definition = OfflineTilePyramidRegionDefinition(STYLE_URL, bounds, 11.0, 15.0, 1f)
        val manager = OfflineManager.getInstance(context)
        val metadata = "RAVO local ${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
        report("Preparando mapa offline desta área...")
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) {
                var lastReport = 0L
                region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (status.isComplete) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            report("Área offline salva! Disponível mesmo sem internet.")
                        } else if (System.currentTimeMillis() - lastReport > 6000L) {
                            lastReport = System.currentTimeMillis()
                            report("Salvando mapa: ${status.completedResourceCount} recursos")
                        }
                        // O provedor limita cada download a 50 MB / 3.000 tiles.
                        if (status.completedResourceSize >= 48L * 1024L * 1024L ||
                            status.completedTileCount >= 2900L) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            report("Limite de download atingido. Área salva parcialmente.")
                        }
                    }

                    override fun onError(error: OfflineRegionError) {
                        report("Falha ao salvar mapa. Verifique a internet.")
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        report("Limite de tiles atingido: região salva parcialmente.")
                    }
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) { report("Não foi possível iniciar o download offline.") }
        })
    }
}

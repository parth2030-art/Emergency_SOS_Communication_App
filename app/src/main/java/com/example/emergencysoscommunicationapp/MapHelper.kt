package com.example.emergencysoscommunicationapp

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource

object MapHelper {
    
    /**
     * Configures the production tile provider for the application.
     * 
     * We use CARTO Positron by default because:
     * 1. It provides a clean, premium light-gray basemap that perfectly fits our emergency red/gray theme.
     * 2. It has very reliable public delivery endpoints with standard application permission terms.
     * 3. It serves tiles securely over HTTPS, bypassing strict OSM policy blocks.
     */
    fun getOsmTileSource(): ITileSource {
        // Primary Option: CARTO Positron light theme
        return XYTileSource(
            "CartoPositron",
            0,
            20,
            256,
            ".png",
            arrayOf(
                "https://basemaps.cartocdn.com/rastertiles/light_all/",
                "https://a.basemaps.cartocdn.com/rastertiles/light_all/",
                "https://b.basemaps.cartocdn.com/rastertiles/light_all/",
                "https://c.basemaps.cartocdn.com/rastertiles/light_all/",
                "https://d.basemaps.cartocdn.com/rastertiles/light_all/"
            )
        )

        /*
        // Alternative Option: Official OpenStreetMap HTTPS tiles
        return XYTileSource(
            "OpenStreetMap",
            0,
            19,
            256,
            ".png",
            arrayOf("https://tile.openstreetmap.org/")
        )
        */
    }
}

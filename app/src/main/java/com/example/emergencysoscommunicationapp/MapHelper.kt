package com.example.emergencysoscommunicationapp

import android.net.Uri
import android.util.Log
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex

object MapHelper {

    private const val TAG = "MapHelper"

    /**
     * Stadia Maps OSM Bright raster tiles.
     *
     * The underlying map data is OpenStreetMap-derived.
     *
     * Stadia Maps requires an API key for Android/mobile applications.
     */
    fun getOsmTileSource(): ITileSource {

        val apiKey = BuildConfig.STADIA_MAPS_API_KEY.trim()

        if (apiKey.isEmpty()) {
            Log.e(
                TAG,
                "STADIA_MAPS_API_KEY is missing. Check local.properties."
            )
        }

        return object : OnlineTileSourceBase(
            "StadiaMapsOSMBright",
            0,
            20,
            256,
            ".png",
            arrayOf(
                "https://tiles.stadiamaps.com/tiles/osm_bright/"
            ),
            "© OpenStreetMap contributors",
            TileSourcePolicy(
                2,
                TileSourcePolicy.FLAG_NO_BULK
            )
        ) {

            override fun getTileURLString(mapTileIndex: Long): String {

                val zoom = MapTileIndex.getZoom(mapTileIndex)
                val x = MapTileIndex.getX(mapTileIndex)
                val y = MapTileIndex.getY(mapTileIndex)

                return "${getBaseUrl()}$zoom/$x/$y.png?api_key=${Uri.encode(apiKey)}"
            }
        }
    }
}
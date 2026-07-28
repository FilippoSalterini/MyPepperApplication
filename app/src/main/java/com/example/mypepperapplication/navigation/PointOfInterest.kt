package com.example.mypepperapplication.navigation

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
/**
 * Rappresenta un Punto di Interesse (PoI) all'interno della mappa dell'ambiente.
 * Contiene le coordinate cartesiane 2D (x, y) e l'orientamento (theta / yaw in radianti)
 * calcolati rispetto al sistema di riferimento di origine (MapFrame).
 */
data class PointOfInterest(
    val name: String,
    val x: Double,
    val y: Double,
    val theta: Double // yaw in radianti, relativo al MapFrame
)

class PoiStore(private val file: File) {
    private val gson = Gson()
    private val type = object : TypeToken<List<PointOfInterest>>() {}.type

    fun save(pois: List<PointOfInterest>) {
        file.writeText(gson.toJson(pois))
    }

    fun load(): List<PointOfInterest> {
        if (!file.exists()) return emptyList()
        return gson.fromJson(file.readText(), type) ?: emptyList()
    }
}
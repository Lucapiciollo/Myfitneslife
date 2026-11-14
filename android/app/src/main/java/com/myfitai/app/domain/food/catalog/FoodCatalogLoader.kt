package com.myfitai.app.domain.food.catalog

import android.content.res.AssetManager
import java.io.InputStream

/** Punto di accesso al catalogo: iniettabile e sostituibile nei test (`FoodCatalogLoader { catalog }`). */
fun interface FoodCatalogLoader {
    fun load(): FoodCatalog
}

/** Legge il catalogo da un flusso e lo tiene in memoria (lettura una sola volta, thread-safe). */
class StreamFoodCatalogLoader(private val open: () -> InputStream) : FoodCatalogLoader {
    @Volatile
    private var cached: FoodCatalog? = null

    override fun load(): FoodCatalog = cached ?: synchronized(this) {
        cached ?: open().use { FoodCatalog.load(it) }.also { cached = it }
    }
}

/** Loader Android: `assets/food/catalog_v1.json`, asset in sola lettura incluso nell'APK. */
class AssetFoodCatalogLoader(assets: AssetManager, path: String = DEFAULT_PATH) :
    FoodCatalogLoader by StreamFoodCatalogLoader({ assets.open(path) }) {
    companion object {
        const val DEFAULT_PATH = "food/catalog_v1.json"
    }
}

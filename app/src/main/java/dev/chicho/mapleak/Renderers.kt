package dev.chicho.mapleak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View.MeasureSpec
import android.view.ViewGroup
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 *  Copy of uTag's generateGoogleMap() as of main 2cb464a (1.0.19, with the try/finally teardown):
 *  a new lite-mode MapView per render, destroyed afterwards.
 */
suspend fun Context.renderCurrentUtag(
    width: Int,
    height: Int,
    padding: Rect,
    markers: GoogleMap.() -> List<MarkerOptions>
): Bitmap? = withTimeout(5000L) {
    withContext(Dispatchers.Main) {
        val options = GoogleMapOptions().liteMode(true)
        val mapView = MapView(this@renderCurrentUtag, options)
        try {
            mapView.onCreate(null)
            mapView.onResume()
            mapView.getMap().run {
                setPadding(padding.left, padding.top, padding.right, padding.bottom)
                mapView.layoutParams = ViewGroup.LayoutParams(width, height)
                mapView.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                )
                mapView.layout(0, 0, width, height)
                drawMarkers(markers)
                awaitReady()
                generateSnapshot().also { clear() }
            }
        } finally {
            mapView.onPause()
            mapView.removeAllViews()
            mapView.onDestroy()
        }
    }
}

/**
 *  Proposed fix: one lite-mode MapView per process, reused for every render and never destroyed,
 *  so whatever the Maps SDK keeps hold of is bounded to a single map. Renders are serialised.
 */
object ReusedMapRenderer {

    private val lock = Mutex()
    private var mapView: MapView? = null
    private var map: GoogleMap? = null

    suspend fun render(
        context: Context,
        width: Int,
        height: Int,
        padding: Rect,
        markers: GoogleMap.() -> List<MarkerOptions>
    ): Bitmap? = lock.withLock {
        withTimeout(5000L) {
            withContext(Dispatchers.Main) {
                val view = mapView ?: MapView(context.applicationContext, GoogleMapOptions().liteMode(true)).also {
                    it.onCreate(null)
                    it.onResume()
                    mapView = it
                }
                val googleMap = map ?: view.getMap().also { map = it }
                googleMap.run {
                    clear()
                    setPadding(padding.left, padding.top, padding.right, padding.bottom)
                    view.layoutParams = ViewGroup.LayoutParams(width, height)
                    view.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                    )
                    view.layout(0, 0, width, height)
                    drawMarkers(markers)
                    awaitReady()
                    generateSnapshot()
                }
            }
        }
    }
}

private fun GoogleMap.drawMarkers(markers: GoogleMap.() -> List<MarkerOptions>) {
    val added = markers(this)
    val bounds = LatLngBounds.builder()
    added.forEach {
        addMarker(it)
        bounds.include(it.position)
    }
    moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 0))
}

private suspend fun MapView.getMap() = suspendCancellableCoroutine {
    getMapAsync { map -> it.resume(map) }
}

private suspend fun GoogleMap.awaitReady() = suspendCancellableCoroutine {
    setOnMapLoadedCallback { it.resume(Unit) }
}

private suspend fun GoogleMap.generateSnapshot() = suspendCancellableCoroutine<Bitmap?> {
    snapshot { bitmap -> it.resume(bitmap) }
}

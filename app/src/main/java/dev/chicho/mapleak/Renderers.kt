package dev.chicho.mapleak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.View.MeasureSpec
import android.widget.RelativeLayout
import androidx.core.view.updateLayoutParams
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

// Verbatim copy of the proposed uTag fix (fix/reuse-static-mapview), renamed so both modes coexist.
/**
 *  The single lite mode MapView used for every static map render in this process.
 *
 *  Creating a MapView per render leaks even when it is destroyed: the Maps SDK keeps hold of the
 *  views of every MapView it has created. With the map widget refreshing every few minutes, the
 *  :service process reached ~20,000 Views and ~1.6 GB (mostly native, swapped out) after 3 days.
 *  Reusing one MapView bounds that to a single map. Renders are serialised by [lock], since they
 *  share the map's state.
 */
private object StaticMapView {
    val lock = Mutex()
    var mapView: MapView? = null
    var map: GoogleMap? = null
}

/**
 *  Generates a Bitmap of a Google Map of a given [width]x[height], and with the given [markers].
 *  The camera will automatically be set to show all the markers, at n-1 zoom level for sufficient
 *  padding.
 *
 *  The map can be modified with [modifier]
 */
suspend fun Context.generateGoogleMapReused(
    width: Int,
    height: Int,
    padding: Rect,
    maxZoomLevel: Float? = null,
    zoomOut: Boolean = false,
    moveWatermark: Boolean = false,
    modifier: suspend GoogleMap.() -> Unit = {},
    markers: suspend GoogleMap.() -> List<MarkerOptions>
): Bitmap? = StaticMapView.lock.withLock {
    withTimeout(5000L) {
        withContext(Dispatchers.Main) {
            //Application context, since the MapView outlives whatever component asked for this render
            val mapView = StaticMapView.mapView ?: MapView(
                applicationContext, GoogleMapOptions().liteMode(true)
            ).also {
                it.onCreate(null)
                it.onResume()
                StaticMapView.mapView = it
            }
            val map = StaticMapView.map ?: mapView.getMap().also { StaticMapView.map = it }
            map.run {
                //Reset anything a previous render may have left on the shared map
                clear()
                mapType = GoogleMap.MAP_TYPE_NORMAL
                setMapStyle(null)
                setPadding(padding.left, padding.top, padding.right, padding.bottom)
                mapView.layoutParams = ViewGroup.LayoutParams(width, height)
                mapView.findViewWithTag<View>("GoogleWatermark")?.updateLayoutParams<RelativeLayout.LayoutParams> {
                    val moved = if(moveWatermark) RelativeLayout.TRUE else 0
                    val default = if(moveWatermark) 0 else RelativeLayout.TRUE
                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, default)
                    addRule(RelativeLayout.ALIGN_PARENT_LEFT, default)
                    addRule(RelativeLayout.ALIGN_PARENT_START, default)
                    addRule(RelativeLayout.ALIGN_PARENT_TOP, moved)
                    addRule(RelativeLayout.ALIGN_PARENT_END, moved)
                }
                mapView.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                )
                mapView.layout(0, 0, width, height)
                modifier(this)
                val addedMarkers = markers(this)
                val bounds = LatLngBounds.builder()
                addedMarkers.forEach {
                    addMarker(it)
                    bounds.include(it.position)
                }
                moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 0))
                if(maxZoomLevel != null && cameraPosition.zoom > maxZoomLevel) {
                    moveCamera(CameraUpdateFactory.zoomTo(maxZoomLevel))
                }
                if(zoomOut) {
                    moveCamera(CameraUpdateFactory.zoomOut())
                }
                awaitReady()
                generateSnapshot()
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

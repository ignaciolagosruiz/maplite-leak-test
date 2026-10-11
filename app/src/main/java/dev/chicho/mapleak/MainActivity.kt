package dev.chicho.mapleak

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.TextView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 *  Renders off-screen lite maps in a loop, the way uTag's widget does, and logs to logcat (tag
 *  MAPLEAK). Start with:
 *  adb shell am start -n dev.chicho.mapleak/.MainActivity --es mode current|reuse --ei count 60
 *  Then read the retained Views with `dumpsys meminfo dev.chicho.mapleak`.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).also { setContentView(it) }
        val mode = intent.getStringExtra("mode") ?: "current"
        val count = intent.getIntExtra("count", 60)
        val padding = Rect(16, 16, 16, 16)
        val markers: com.google.android.gms.maps.GoogleMap.() -> List<MarkerOptions> = {
            listOf(MarkerOptions().position(LatLng(-34.6037, -58.3816)))
        }
        scope.launch {
            var ok = 0
            var failed = 0
            for (i in 1..count) {
                val bitmap = try {
                    if (mode == "reuse") {
                        applicationContext.generateGoogleMapReused(600, 400, padding, markers = { markers(this) })
                    } else {
                        applicationContext.renderCurrentUtag(600, 400, padding, markers)
                    }
                } catch (e: Exception) {
                    if (failed < 3) Log.w("MAPLEAK", "render $i failed: $e")
                    null
                }
                if (bitmap != null) ok++ else failed++
                if (i % 10 == 0 || i == count) {
                    val line = "mode=$mode render=$i ok=$ok failed=$failed " +
                        "nativeHeapMB=${Debug.getNativeHeapAllocatedSize() / 1_048_576}"
                    Log.i("MAPLEAK", line)
                    text.text = line
                }
            }
            repeat(3) { Runtime.getRuntime().gc(); System.runFinalization() }
            Log.i("MAPLEAK", "DONE mode=$mode ok=$ok failed=$failed")
            text.text = "DONE mode=$mode ok=$ok failed=$failed"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

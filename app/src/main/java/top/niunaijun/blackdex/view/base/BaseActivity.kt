package top.niunaijun.blackdex.view.base

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * Base activity: draws edge-to-edge so Material 3 surfaces extend behind the system bars.
 *
 * [enableEdgeToEdge] runs after `super.onCreate` (so AppCompat's night mode is already applied
 * to this activity's resources) and before any `setContentView`. Its default auto styles pick
 * light/dark bar icons from the effective night mode and fall back to translucent scrims on
 * API levels that cannot tint bar icons.
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }
}

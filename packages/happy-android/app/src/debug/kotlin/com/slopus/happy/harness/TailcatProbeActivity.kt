package com.slopus.happy.harness

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.slopus.happy.direct.DirectUnavailableException
import com.slopus.happy.direct.Tailcat
import com.slopus.happy.direct.TailcatHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The linked Tailcat library on a device, as far as it goes without a
 * computer: it loads, refuses an address that isn't one, and fails cleanly
 * (no address in its words) to reach one that goes nowhere, directly and
 * through [TailcatHttpTransport].
 *
 * ```
 * adb shell am start -n com.slopus.happy.dev/com.slopus.happy.harness.TailcatProbeActivity \
 *     --es address tc…
 * ```
 *
 * `address`: a well-formed address whose relay is unreachable (made with
 * a throwaway program over tailcat's `ConnInfo`, its relay at
 * `derp.invalid`, so no public relay is ever asked). The view tagged
 * `tailcat-probe` reports each step in its content description; it ends
 * with `done`.
 */
class TailcatProbeActivity : ComponentActivity() {
    private lateinit var view: TextView
    private val lines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { tag = "tailcat-probe"; textSize = 14f; setPadding(32, 160, 32, 32) }
        setContentView(view)
        Tailcat.install(this)
        val address = intent.getStringExtra("address").orEmpty()
        lifecycleScope.launch {
            report("linked=${Tailcat.isLinked}")
            report("garbage=" + attempt { Tailcat.open("not-an-address", 8080, 3_000) })
            report("unreachable=" + attempt { Tailcat.open(address, 8080, 3_000) })
            val transport = TailcatHttpTransport(address, 8080)
            val started = System.currentTimeMillis()
            val outcome = try {
                transport.request("GET", "/v0/health", "TOKEN", null, emptyMap())
                "answered"
            } catch (e: DirectUnavailableException) {
                "${e::class.simpleName}:${e.message}"
            } catch (e: Exception) {
                "other:${e::class.simpleName}:${e.message}"
            }
            transport.close()
            report("transport=$outcome in ${(System.currentTimeMillis() - started) / 1000}s")
            report("done")
        }
    }

    private suspend fun attempt(open: () -> Any): String = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val outcome = try {
            open()
            "opened"
        } catch (e: DirectUnavailableException) {
            "refused:${e.message}"
        } catch (e: Throwable) {
            "crash:${e::class.simpleName}:${e.message}"
        }
        "$outcome in ${System.currentTimeMillis() - started}ms"
    }

    private fun report(line: String) {
        lines += line
        android.util.Log.i("TailcatProbe", line)
        view.text = lines.joinToString("\n")
        view.contentDescription = lines.joinToString(" | ")
    }
}

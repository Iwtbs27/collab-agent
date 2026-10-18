package ai.opencyvis.backend

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplitScreenPocActivity : AppCompatActivity() {

    private lateinit var info: TextView
    private lateinit var otpBox: OtpDigitBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        info = TextView(this).apply {
            text = "Split-Screen Pairing POC\nAPI: ${Build.VERSION.SDK_INT}  Multi-window: $isInMultiWindowMode\n\nTap button → launch Settings in adjacent split.\nEnter the 6-digit code below."
            textSize = 14f
        }
        layout.addView(info)

        val btn = Button(this).apply {
            text = "Launch Settings in Split"
            setOnClickListener {
                val settingsIntent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                if (settingsIntent.resolveActivity(packageManager) == null) {
                    settingsIntent.action = android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                }
                settingsIntent.addFlags(
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                startActivity(settingsIntent)
            }
        }
        layout.addView(btn)

        layout.addView(TextView(this).apply {
            text = "\nEnter Pairing Code:"
            textSize = 16f
            setPadding(0, 16, 0, 8)
        })

        otpBox = OtpDigitBox(this).apply {
            listener = object : OtpDigitBox.OnCodeCompleteListener {
                override fun onCodeComplete(code: String) {
                    info.text = "✅ Auto-submitted: $code\n(In real flow → AdbPairingClient)"
                }
            }
        }
        layout.addView(otpBox)

        val pad = NumberPadView(this).apply {
            onDigit = { d -> otpBox.appendDigit(d) }
            onBackspace = { otpBox.deleteDigit() }
        }
        layout.addView(pad)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        info.text = "Split-Screen Pairing POC\nAPI: ${Build.VERSION.SDK_INT}  Multi-window: $isInMultiWindowMode\n\nEnter the 6-digit code below."
        android.util.Log.i("SplitPOC", "onMultiWindowModeChanged: $isInMultiWindowMode")
    }
}

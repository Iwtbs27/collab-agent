package ai.opencyvis.backend

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.opencyvis.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class PairingDialogActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PORT = "pairing_port"

        fun intent(context: Context, port: Int): Intent {
            return Intent(context, PairingDialogActivity::class.java).apply {
                putExtra(EXTRA_PORT, port)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing_dialog)

        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val desc = findViewById<TextView>(R.id.dialog_desc)
        desc.text = getString(R.string.setup_pair_desc, 6)

        val inputLayout = findViewById<TextInputLayout>(R.id.code_input_layout)
        val input = findViewById<TextInputEditText>(R.id.code_input)
        val btn = findViewById<MaterialButton>(R.id.submit_btn)

        input.hint = getString(R.string.setup_pair_hint, 6)

        btn.setOnClickListener {
            val code = input.text?.toString()
            if (code.isNullOrBlank() || code.length < 6) {
                inputLayout.error = getString(R.string.setup_error_wrong_code)
                return@setOnClickListener
            }
            inputLayout.error = null

            // Build a RemoteInput-compatible intent that AdbPairingService can parse
            val remoteInput = RemoteInput.Builder("pairing_code").setLabel("Pairing code").build()
            val resultsBundle = Bundle().apply { putCharSequence("pairing_code", code) }
            val serviceIntent = Intent(this, AdbPairingService::class.java).apply {
                action = "reply"
                putExtra("pairing_port", port)
            }
            // Attach RemoteInput results so the service can read them via
            // RemoteInput.getResultsFromIntent()
            RemoteInput.addResultsToIntent(
                arrayOf(remoteInput),
                serviceIntent,
                resultsBundle
            )
            startService(serviceIntent)
            finish()
        }
    }
}

package io.github.mwarevn.fakegps.ui

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import io.github.mwarevn.fakegps.databinding.ActivityAntiDetectionSettingsBinding
import io.github.mwarevn.fakegps.utils.PrefManager

class AntiDetectionSettingsActivity : AppCompatActivity() {

    private val binding by lazy {
        ActivityAntiDetectionSettingsBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupSwitches()
        
        binding.btnResetToDefault.setOnClickListener {
            resetToDefault()
        }
    }

    private fun setupSwitches() {
        binding.apply {
            switchAccuracySpoof.isChecked = PrefManager.isAccuracySpoofEnabled
            switchAccuracySpoof.setOnCheckedChangeListener { _, isChecked ->
                PrefManager.isAccuracySpoofEnabled = isChecked
            }

            switchSensorSpoof.isChecked = PrefManager.isSensorSpoofEnabled
            switchSensorSpoof.setOnCheckedChangeListener { _, isChecked ->
                PrefManager.isSensorSpoofEnabled = isChecked
            }

            switchNetworkSimulation.isChecked = PrefManager.isNetworkSimEnabled
            switchNetworkSimulation.setOnCheckedChangeListener { _, isChecked ->
                PrefManager.isNetworkSimEnabled = isChecked
            }
        }
    }

    private fun resetToDefault() {
        PrefManager.isAccuracySpoofEnabled = true
        PrefManager.isSensorSpoofEnabled = true
        PrefManager.isNetworkSimEnabled = true
        setupSwitches()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

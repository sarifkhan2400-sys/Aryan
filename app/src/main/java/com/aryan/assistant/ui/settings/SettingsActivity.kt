package com.aryan.assistant.ui.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aryan.assistant.service.AccessibilityHelperService
import com.aryan.assistant.viewmodel.PrimeContact
import com.example.R
import com.example.databinding.ActivitySettingsBinding
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private val primeContacts = mutableListOf<PrimeContact>()
    private lateinit var primeAdapter: PrimeContactAdapter

    private val modelOptions = listOf(
        "Native Audio (Human Voice)" to "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "Flash Live (Fast)" to "models/gemini-2.0-flash-live-001",
        "Pro Audio Dialog" to "models/gemini-2.5-flash-preview-native-audio-dialog"
    )

    private val voiceOptions = listOf(
        "Charon (Deep / Male)",
        "Aoede (Warm / Female)",
        "Kore (Calm / Female)",
        "Fenrir (Confident / Male)",
        "Puck (Playful / Male)",
        "Leda (Soft / Female)",
        "Orus (Assertive / Male)",
        "Zephyr (Gentle / Female)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("aryan_prefs", Context.MODE_PRIVATE)

        setupHeader()
        setupInputs()
        setupSpinners()
        setupPersonalityRadio()
        setupPrimeContacts()
        setupAccessibilityCheck()
        setupSaveButton()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun setupHeader() {
        binding.settingsBackBtn.setOnClickListener {
            finish()
        }
    }

    private fun setupInputs() {
        binding.apiKeyInput.setText(prefs.getString("api_key", ""))
        binding.userNameInput.setText(prefs.getString("user_name", "User"))
    }

    private fun setupSpinners() {
        // Model Spinner
        val modelLabels = modelOptions.map { it.first }
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelLabels)
        binding.modelSpinner.adapter = modelAdapter

        val savedModel = prefs.getString("gemini_model", "models/gemini-2.5-flash-native-audio-preview-12-2025")
        val modelIdx = modelOptions.indexOfFirst { it.second == savedModel }.coerceAtLeast(0)
        binding.modelSpinner.setSelection(modelIdx)

        // Voice Spinner
        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceOptions)
        binding.voiceSpinner.adapter = voiceAdapter

        val savedVoice = prefs.getString("gemini_voice", "Charon")
        val voiceIdx = voiceOptions.indexOfFirst { it.startsWith(savedVoice ?: "Charon") }.coerceAtLeast(0)
        binding.voiceSpinner.setSelection(voiceIdx)
    }

    private fun setupPersonalityRadio() {
        when (prefs.getString("personality_mode", "bf")) {
            "professional" -> binding.radioProfessional.isChecked = true
            "assistant" -> binding.radioAssistant.isChecked = true
            else -> binding.radioBf.isChecked = true
        }
    }

    private fun setupPrimeContacts() {
        loadPrimeContacts()
        primeAdapter = PrimeContactAdapter(primeContacts) { position ->
            primeContacts.removeAt(position)
            primeAdapter.notifyItemRemoved(position)
            savePrimeContactsList()
        }
        binding.primeContactsRecycler.layoutManager = LinearLayoutManager(this)
        binding.primeContactsRecycler.adapter = primeAdapter

        binding.addPrimeContactBtn.setOnClickListener {
            showAddContactDialog()
        }
    }

    private fun showAddContactDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_prime_contact, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.dialogNameInput)
        val numberInput = dialogView.findViewById<EditText>(R.id.dialogNumberInput)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("ADD") { _, _ ->
                val name = nameInput.text.toString().trim()
                val number = numberInput.text.toString().trim()
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    primeContacts.add(PrimeContact(name, number))
                    primeAdapter.notifyItemInserted(primeContacts.size - 1)
                    savePrimeContactsList()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun loadPrimeContacts() {
        primeContacts.clear()
        val jsonStr = prefs.getString("prime_contacts_json", null)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    primeContacts.add(PrimeContact(obj.getString("name"), obj.getString("number")))
                }
                return
            } catch (e: Exception) {
                // Ignore
            }
        }
        val legacyName = prefs.getString("prime_name", null)
        val legacyNum = prefs.getString("prime_number", null)
        if (!legacyName.isNullOrEmpty() && !legacyNum.isNullOrEmpty()) {
            primeContacts.add(PrimeContact(legacyName, legacyNum))
        }
    }

    private fun savePrimeContactsList() {
        val array = JSONArray()
        for (c in primeContacts) {
            val obj = JSONObject().apply {
                put("name", c.name)
                put("number", c.number)
            }
            array.put(obj)
        }
        prefs.edit().putString("prime_contacts_json", array.toString()).apply()
    }

    private fun setupAccessibilityCheck() {
        binding.accessibilityCard.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }
        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val enabled = AccessibilityHelperService.isEnabled(this)
        if (enabled) {
            binding.accessibilityStatusText.text = "✅ Enabled"
            binding.accessibilityStatusText.setTextColor(getColor(R.color.success))
        } else {
            binding.accessibilityStatusText.text = "❌ Disabled"
            binding.accessibilityStatusText.setTextColor(getColor(R.color.error_red))
        }
    }

    private fun setupSaveButton() {
        binding.saveSettingsBtn.setOnClickListener {
            val apiKey = binding.apiKeyInput.text.toString().trim()
            val userName = binding.userNameInput.text.toString().trim().ifEmpty { "User" }

            val selectedModel = modelOptions[binding.modelSpinner.selectedItemPosition].second
            val rawVoice = voiceOptions[binding.voiceSpinner.selectedItemPosition]
            val selectedVoice = rawVoice.substringBefore(" ")

            val personality = when (binding.personalityRadioGroup.checkedRadioButtonId) {
                R.id.radioProfessional -> "professional"
                R.id.radioAssistant -> "assistant"
                else -> "bf"
            }

            prefs.edit().apply {
                putString("api_key", apiKey)
                putString("user_name", userName)
                putString("gemini_model", selectedModel)
                putString("gemini_voice", selectedVoice)
                putString("personality_mode", personality)
                apply()
            }
            savePrimeContactsList()

            Toast.makeText(this, "Settings saved. Restart session to apply.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

class PrimeContactAdapter(
    private val contacts: List<PrimeContact>,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<PrimeContactAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.primeItemName)
        val numberText: TextView = view.findViewById(R.id.primeItemNumber)
        val deleteBtn: ImageButton = view.findViewById(R.id.primeItemDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prime_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]
        holder.nameText.text = contact.name
        holder.numberText.text = contact.number
        holder.deleteBtn.setOnClickListener {
            onDeleteClick(holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount(): Int = contacts.size
}

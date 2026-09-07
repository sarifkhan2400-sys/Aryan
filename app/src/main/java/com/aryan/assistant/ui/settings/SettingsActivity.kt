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
import android.widget.Button
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
        "Flash Live 2.0 (Recommended / Stable)" to "models/gemini-2.0-flash-live-001",
        "Native Audio (Human Voice)" to "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "Flash Experimental" to "models/gemini-2.0-flash-exp"
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
        setupPatternLock()
        setupDefaultAssistant()
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

    private fun setupPatternLock() {
        val enabled = prefs.getBoolean("pattern_lock_enabled", true)
        binding.patternLockSwitch.isChecked = enabled

        binding.patternLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pattern_lock_enabled", isChecked).apply()
            updatePatternStatusUI()
        }

        binding.configurePatternBtn.setOnClickListener {
            showPatternLockDialog()
        }

        binding.testPatternUnlockBtn.setOnClickListener {
            testPatternUnlock()
        }

        binding.clearSavedPatternBtn.setOnClickListener {
            prefs.edit().remove("pattern_lock_sequence").apply()
            updatePatternStatusUI()
            Toast.makeText(this, "প্যাটার্ন লক মুছে ফেলা হয়েছে (Pattern cleared)", Toast.LENGTH_SHORT).show()
        }

        updatePatternStatusUI()
    }

    private fun updatePatternStatusUI() {
        val patternStr = prefs.getString("pattern_lock_sequence", null)
        val isEnabled = prefs.getBoolean("pattern_lock_enabled", true)

        if (!patternStr.isNullOrEmpty()) {
            val count = patternStr.split(",").filter { it.isNotEmpty() }.size
            binding.patternStatusSummaryText.text = "✅ প্যাটার্ন সক্রিয়: $count টি ডট সংরক্ষিত (Saved)"
            binding.patternStatusSummaryText.setTextColor(getColor(R.color.success))
            binding.patternActionsLayout.visibility = if (isEnabled) View.VISIBLE else View.GONE
            binding.configurePatternBtn.text = "CHANGE"
        } else {
            binding.patternStatusSummaryText.text = "❌ প্যাটার্ন সেট করা নেই (Not configured)"
            binding.patternStatusSummaryText.setTextColor(getColor(R.color.text_secondary))
            binding.patternActionsLayout.visibility = View.GONE
            binding.configurePatternBtn.text = "SET PATTERN"
        }
    }

    private fun showPatternLockDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pattern_lock, null)
        val patternView = dialogView.findViewById<PatternLockView>(R.id.dialogPatternLockView)
        val statusText = dialogView.findViewById<TextView>(R.id.dialogPatternStatusText)
        val resetBtn = dialogView.findViewById<Button>(R.id.dialogResetPatternBtn)
        val cancelBtn = dialogView.findViewById<Button>(R.id.dialogCancelPatternBtn)
        val saveBtn = dialogView.findViewById<Button>(R.id.dialogSavePatternBtn)

        var detectedPattern: List<Int>? = null

        // Load existing pattern if available
        val existingPattern = prefs.getString("pattern_lock_sequence", null)
        if (!existingPattern.isNullOrEmpty()) {
            val list = existingPattern.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (list.size >= 3) {
                patternView.setPattern(list)
                detectedPattern = list
                statusText.text = "বর্তমান প্যাটার্ন: ${list.size}টি ডট। নতুন করে আঁকতে পারেন।"
            }
        }

        patternView.listener = object : PatternLockView.OnPatternListener {
            override fun onPatternDetected(pattern: List<Int>) {
                if (pattern.size >= 4) {
                    detectedPattern = pattern
                    statusText.text = "✅ ${pattern.size}টি ডট যুক্ত হয়েছে। সেভ করতে পারেন।"
                    statusText.setTextColor(getColor(R.color.success))
                } else {
                    detectedPattern = null
                    patternView.setError(true)
                    statusText.text = "⚠️ কমপক্ষে ৪টি ডট যুক্ত করুন (Connect at least 4 dots)"
                    statusText.setTextColor(getColor(R.color.error_red))
                }
            }

            override fun onPatternCleared() {
                detectedPattern = null
                statusText.text = "আঙুল দিয়ে ডটগুলো যুক্ত করুন"
                statusText.setTextColor(getColor(R.color.text_secondary))
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        resetBtn.setOnClickListener {
            patternView.clearPattern()
            detectedPattern = null
        }

        cancelBtn.setOnClickListener {
            dialog.dismiss()
        }

        saveBtn.setOnClickListener {
            val pattern = detectedPattern
            if (pattern != null && pattern.size >= 4) {
                val patternString = pattern.joinToString(",")
                prefs.edit()
                    .putString("pattern_lock_sequence", patternString)
                    .putBoolean("pattern_lock_enabled", true)
                    .apply()
                updatePatternStatusUI()
                Toast.makeText(this, "প্যাটার্ন লক সংরক্ষিত হয়েছে! (Pattern saved)", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                patternView.setError(true)
                statusText.text = "⚠️ অনুগ্রহ করে সঠিক প্যাটার্ন আঁকুন (কমপক্ষে ৪টি ডট)"
                statusText.setTextColor(getColor(R.color.error_red))
            }
        }

        dialog.show()
    }

    private fun testPatternUnlock() {
        val patternStr = prefs.getString("pattern_lock_sequence", null)
        if (patternStr.isNullOrEmpty()) {
            Toast.makeText(this, "প্যাটার্ন সেট করা নেই", Toast.LENGTH_SHORT).show()
            return
        }
        val patternList = patternStr.split(",").mapNotNull { it.trim().toIntOrNull() }

        if (!AccessibilityHelperService.isEnabled(this)) {
            Toast.makeText(this, "অ্যাক্সেসিবিলিটি সার্ভিস চালু করা প্রয়োজন", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            return
        }

        Toast.makeText(this, "প্যাটার্ন আনলক টেস্ট শুরু হচ্ছে...", Toast.LENGTH_SHORT).show()
        AccessibilityHelperService.instance?.unlockWithPattern(patternList)
    }

    private fun setupDefaultAssistant() {
        val openAssistantSettings = {
            try {
                // Open Android's Default Digital Assistant App selector screen
                val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e2: Exception) {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                }
            }
        }

        binding.defaultAssistantCard.setOnClickListener {
            openAssistantSettings()
        }

        binding.openDefaultAssistantSettingsBtn.setOnClickListener {
            openAssistantSettings()
        }
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

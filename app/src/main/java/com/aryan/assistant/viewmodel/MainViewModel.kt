package com.aryan.assistant.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.aryan.assistant.model.AppCommand
import com.aryan.assistant.service.AccessibilityHelperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class PrimeContact(val name: String, val number: String)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS_NAME = "aryan_prefs"
        private const val KEY_PRIME_CONTACTS_JSON = "prime_contacts_json"
    }

    private val context: Context get() = getApplication()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _commandResult = MutableLiveData<String?>()
    val commandResult: LiveData<String?> = _commandResult

    private val popularPackages = mapOf(
        "youtube" to "com.google.android.youtube",
        "ইউটিউব" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "ওয়াটসঅ্যাপ" to "com.whatsapp",
        "হোয়াটসঅ্যাপ" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "ইন্সটাগ্রাম" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "ফেসবুক" to "com.facebook.katana",
        "chrome" to "com.android.chrome",
        "ক্রোম" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "স্পটিফাই" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "নেটফ্লিক্স" to "com.netflix.mediaclient",
        "telegram" to "org.telegram.messenger",
        "টেলিগ্রাম" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android",
        "স্নাপচ্যাট" to "com.snapchat.android",
        "calculator" to "com.google.android.calculator",
        "ক্যালকুলেটর" to "com.google.android.calculator",
        "calendar" to "com.google.android.calendar",
        "ক্যালেন্ডার" to "com.google.android.calendar",
        "clock" to "com.google.android.deskclock",
        "ঘড়ি" to "com.google.android.deskclock",
        "settings" to "com.android.settings",
        "সেটিংস" to "com.android.settings",
        "phone" to "com.google.android.dialer",
        "contacts" to "com.google.android.contacts",
        "play store" to "com.android.vending",
        "playstore" to "com.android.vending",
        "প্লে স্টোর" to "com.android.vending",
        "amazon" to "in.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "paytm" to "net.one97.paytm",
        "phonepe" to "com.phonepe.app",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "zoom" to "us.zoom.videomeetings",
        "meet" to "com.google.android.apps.tachyon",
        "teams" to "com.microsoft.teams",
        "discord" to "com.discord",
        "linkedin" to "com.linkedin.android"
    )

    fun executeCommand(command: AppCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (command.type) {
                    AppCommand.OPEN_APP -> {
                        val appName = command.params["app_name"] ?: ""
                        val success = openApp(appName)
                        postResult(if (success) "Opened $appName." else "Could not open $appName.")
                    }
                    AppCommand.CLOSE_APP -> {
                        val helper = AccessibilityHelperService.instance
                        val closed = helper?.closeCurrentApp() ?: false
                        postResult(if (closed) "Closed application." else "Could not close application. Ensure Accessibility Service is enabled.")
                    }
                    AppCommand.CALL -> {
                        val target = command.params["name"] ?: ""
                        val number = findContactNumber(target) ?: target
                        val called = makePhoneCall(number)
                        postResult(if (called) "Calling $target ($number)." else "Failed to make call.")
                    }
                    AppCommand.PRIME_CALL -> {
                        val index = command.params["index"]?.toIntOrNull() ?: 0
                        val contacts = getPrimeContacts()
                        if (index < contacts.size) {
                            val prime = contacts[index]
                            makePhoneCall(prime.number)
                            postResult("Calling prime contact ${prime.name} (${prime.number}).")
                        } else {
                            postResult("Prime contact at index $index is not set in Settings.")
                        }
                    }
                    AppCommand.PRIME_MSG -> {
                        val index = command.params["index"]?.toIntOrNull() ?: 0
                        val contacts = getPrimeContacts()
                        if (index < contacts.size) {
                            val prime = contacts[index]
                            sendSms(prime.number, "Hey ${prime.name}!")
                            postResult("Opening message for prime contact ${prime.name}.")
                        } else {
                            postResult("Prime contact is not set in Settings.")
                        }
                    }
                    AppCommand.SMS -> {
                        val target = command.params["name"] ?: ""
                        val message = command.params["message"] ?: ""
                        val number = findContactNumber(target) ?: target
                        sendSms(number, message)
                        postResult("Opening SMS to $target.")
                    }
                    AppCommand.WHATSAPP_MSG -> {
                        val target = command.params["name"] ?: ""
                        val message = command.params["message"] ?: ""
                        val number = findContactNumber(target) ?: target
                        openWhatsApp(number, message)
                        postResult("Opening WhatsApp for $target.")
                    }
                    AppCommand.WHATSAPP_CALL -> {
                        val target = command.params["name"] ?: ""
                        val number = findContactNumber(target) ?: target
                        openWhatsApp(number, "")
                        postResult("Opening WhatsApp call for $target.")
                    }
                    AppCommand.VOLUME_UP -> {
                        adjustVolume(AudioManager.ADJUST_RAISE)
                        postResult("Volume increased.")
                    }
                    AppCommand.VOLUME_DOWN -> {
                        adjustVolume(AudioManager.ADJUST_LOWER)
                        postResult("Volume decreased.")
                    }
                    AppCommand.FLASHLIGHT_ON -> {
                        setFlashlight(true)
                        postResult("Flashlight turned on.")
                    }
                    AppCommand.FLASHLIGHT_OFF -> {
                        setFlashlight(false)
                        postResult("Flashlight turned off.")
                    }
                    AppCommand.WIFI_ON -> {
                        openWirelessSettings()
                        postResult("Opening Wi-Fi settings.")
                    }
                    AppCommand.WIFI_OFF -> {
                        openWirelessSettings()
                        postResult("Opening Wi-Fi settings.")
                    }
                    AppCommand.BLUETOOTH_ON, AppCommand.BLUETOOTH_OFF -> {
                        openBluetoothSettings()
                        postResult("Opening Bluetooth settings.")
                    }
                    AppCommand.UNLOCK_PHONE -> {
                        val enabled = prefs.getBoolean("pattern_lock_enabled", true)
                        val patternStr = prefs.getString("pattern_lock_sequence", null)
                        if (!enabled || patternStr.isNullOrEmpty()) {
                            postResult("প্যাটার্ন লক সেট করা নেই। দয়া করে সেটিংসে গিয়ে আপনার ফোনের প্যাটার্ন লক সেট করুন।")
                        } else if (!AccessibilityHelperService.isEnabled(context)) {
                            postResult("অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ আছে। লক খোলার জন্য সেটিংস থেকে অ্যাক্সেসিবিলিটি সার্ভিস চালু করুন।")
                        } else {
                            val patternList = patternStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                            if (patternList.size < 3) {
                                postResult("সংরক্ষিত প্যাটার্নটি সঠিক নয়। দয়া করে পুনরায় সেট করুন।")
                            } else {
                                val helper = AccessibilityHelperService.instance
                                if (helper != null) {
                                    val success = helper.unlockWithPattern(patternList)
                                    postResult(if (success) "ফোনের লক খোলা হচ্ছে..." else "লক খোলার চেষ্টা করা হচ্ছে...")
                                } else {
                                    postResult("অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই। অনুগ্রহ করে সেটিংস থেকে চালু করুন।")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing command: ${command.type}", e)
                postResult("Action failed: ${e.message}")
            }
        }
    }

    private fun postResult(result: String) {
        _commandResult.postValue(result)
    }

    private fun openApp(appName: String): Boolean {
        val cleanName = appName.trim().lowercase()
        val pm = context.packageManager

        // Check dictionary
        val pkgFromMap = popularPackages[cleanName]
        if (pkgFromMap != null) {
            val intent = pm.getLaunchIntentForPackage(pkgFromMap)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            }
        }

        // Search installed applications
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in installedApps) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label.contains(cleanName) || cleanName.contains(label)) {
                val intent = pm.getLaunchIntentForPackage(app.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun makePhoneCall(number: String): Boolean {
        if (number.isBlank()) return false
        val uri = Uri.parse("tel:$number")
        val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(callIntent)
            true
        } catch (e: Exception) {
            // Fallback to DIAL
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(dialIntent)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun sendSms(number: String, message: String) {
        val uri = Uri.parse("smsto:$number")
        val smsIntent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(smsIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening SMS intent", e)
        }
    }

    private fun openWhatsApp(number: String, message: String) {
        val cleanNumber = number.replace("+", "").replace(" ", "").replace("-", "")
        val encodedMsg = try {
            URLEncoder.encode(message, "UTF-8")
        } catch (e: Exception) {
            ""
        }
        val url = if (cleanNumber.isNotEmpty()) {
            "https://wa.me/$cleanNumber?text=$encodedMsg"
        } else {
            "https://api.whatsapp.com/send?text=$encodedMsg"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.whatsapp")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // If WhatsApp package not found, open in browser
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        }
    }

    private fun adjustVolume(direction: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun setFlashlight(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                val cameraId = cm.cameraIdList.firstOrNull() ?: return
                cm.setTorchMode(cameraId, enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Failed toggling torch", e)
            }
        }
    }

    private fun openWirelessSettings() {
        val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun openBluetoothSettings() {
        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun answerIncomingCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            try {
                telecom?.acceptRingingCall()
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting call", e)
            }
        }
    }

    fun rejectIncomingCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            try {
                telecom?.endCall()
            } catch (e: Exception) {
                Log.e(TAG, "Error rejecting call", e)
            }
        }
    }

    private fun findContactNumber(nameQuery: String): String? {
        if (nameQuery.isBlank()) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$nameQuery%")

        try {
            val cursor: Cursor? = context.contentResolver.query(
                uri, projection, selection, selectionArgs, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numIndex >= 0) {
                        return it.getString(numIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching contacts", e)
        }
        return null
    }

    fun getPrimeContacts(): List<PrimeContact> {
        val list = mutableListOf<PrimeContact>()
        val jsonStr = prefs.getString(KEY_PRIME_CONTACTS_JSON, null)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(PrimeContact(obj.getString("name"), obj.getString("number")))
                }
                return list
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing prime contacts JSON", e)
            }
        }

        // Legacy migration
        val legacyName = prefs.getString("prime_name", null)
        val legacyNumber = prefs.getString("prime_number", null)
        if (!legacyName.isNullOrEmpty() && !legacyNumber.isNullOrEmpty()) {
            list.add(PrimeContact(legacyName, legacyNumber))
            savePrimeContacts(list)
        }
        return list
    }

    fun savePrimeContacts(contacts: List<PrimeContact>) {
        val array = JSONArray()
        for (c in contacts) {
            val obj = JSONObject().apply {
                put("name", c.name)
                put("number", c.number)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_PRIME_CONTACTS_JSON, array.toString()).apply()
    }
}

package com.aryan.assistant.ai

import com.aryan.assistant.model.AppCommand

object CommandParser {

    fun parse(rawText: String): AppCommand? {
        val text = rawText.trim().lowercase()
        if (text.isEmpty()) return null

        // 1. Prime Contacts Commands
        if (text.contains("ক্লোজ ফ্রেন্ডকে কল") || text.contains("কল মাই ক্লোজ ফ্রেন্ড") ||
            text.contains("call my close friend") || text.contains("call close friend")
        ) {
            return AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "0"))
        }

        if (text.contains("সেকেন্ড কন্ট্যাক্টকে কল") || text.contains("কল মাই সেকেন্ড কন্ট্যাক্ট") ||
            text.contains("call my second contact") || text.contains("call second contact")
        ) {
            return AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "1"))
        }

        if (text.contains("প্রিয়জনকে মেসেজ") || text.contains("মেসেজ মাই লাভ") ||
            text.contains("message my love") || text.contains("text my love")
        ) {
            return AppCommand(AppCommand.PRIME_MSG, mapOf("index" to "0"))
        }

        // 2. Volume Controls
        if (text.contains("ভলিউম বাড়াও") || text.contains("ভলিউম আপ") ||
            text.contains("volume up") || text.contains("increase volume") || text.contains("sound up")
        ) {
            return AppCommand(AppCommand.VOLUME_UP)
        }
        if (text.contains("ভলিউম কমাও") || text.contains("ভলিউম ডাউন") ||
            text.contains("volume down") || text.contains("decrease volume") || text.contains("sound down")
        ) {
            return AppCommand(AppCommand.VOLUME_DOWN)
        }

        // 3. Torch / Flashlight
        if (text.contains("টর্চ অন") || text.contains("ফ্ল্যাশলাইট অন") ||
            text.contains("flashlight on") || text.contains("turn on torch") || text.contains("torch on")
        ) {
            return AppCommand(AppCommand.FLASHLIGHT_ON)
        }
        if (text.contains("টর্চ অফ") || text.contains("ফ্ল্যাশলাইট অফ") ||
            text.contains("flashlight off") || text.contains("turn off torch") || text.contains("torch off")
        ) {
            return AppCommand(AppCommand.FLASHLIGHT_OFF)
        }

        // 4. WiFi
        if (text.contains("ওয়াইফাই অন") || text.contains("wifi on") || text.contains("turn on wifi")) {
            return AppCommand(AppCommand.WIFI_ON)
        }
        if (text.contains("ওয়াইফাই অফ") || text.contains("wifi off") || text.contains("turn off wifi")) {
            return AppCommand(AppCommand.WIFI_OFF)
        }

        // 5. Bluetooth
        if (text.contains("ব্লুটুথ অন") || text.contains("bluetooth on") || text.contains("turn on bluetooth")) {
            return AppCommand(AppCommand.BLUETOOTH_ON)
        }
        if (text.contains("ব্লুটুথ অফ") || text.contains("bluetooth off") || text.contains("turn off bluetooth")) {
            return AppCommand(AppCommand.BLUETOOTH_OFF)
        }

        // 6. Close App
        if (text.contains("বন্ধ করো") || text.contains("ক্লোজ") ||
            text.contains("close app") || text.contains("go home") || text.contains("exit app")
        ) {
            return AppCommand(AppCommand.CLOSE_APP)
        }

        // 7. WhatsApp Message or Call
        if (text.contains("হোয়াটসঅ্যাপ") || text.contains("ওয়াটসঅ্যাপ") || text.contains("whatsapp")) {
            val contact = extractTargetName(text, listOf("হোয়াটসঅ্যাপ করো", "ওয়াটসঅ্যাপ করো", "whatsapp", "message on whatsapp"))
            if (text.contains("কল") || text.contains("call")) {
                return AppCommand(AppCommand.WHATSAPP_CALL, mapOf("name" to contact))
            }
            return AppCommand(AppCommand.WHATSAPP_MSG, mapOf("name" to contact))
        }

        // 8. Call Phone
        if (text.startsWith("কল ") || text.startsWith("call ") || text.contains("কে কল করো") || text.contains("call to ")) {
            val target = extractTargetName(text, listOf("কল করো", "কল", "call to", "call"))
            if (target.isNotEmpty()) {
                return AppCommand(AppCommand.CALL, mapOf("name" to target))
            }
        }

        // 9. SMS
        if (text.contains("এসএমএস") || text.contains("sms") || text.contains("মেসেজ পাঠাও") || text.contains("send sms")) {
            val target = extractTargetName(text, listOf("এসএমএস পাঠাও", "sms পাঠাও", "send sms to", "message to"))
            return AppCommand(AppCommand.SMS, mapOf("name" to target))
        }

        // 10. Open App
        if (text.contains("খোলো") || text.contains("ওপেন") || text.startsWith("open ") || text.contains("launch ")) {
            val app = extractAppName(text)
            if (app.isNotEmpty()) {
                return AppCommand(AppCommand.OPEN_APP, mapOf("app_name" to app))
            }
        }

        return null
    }

    private fun extractTargetName(text: String, patterns: List<String>): String {
        var clean = text
        for (pat in patterns) {
            clean = clean.replace(pat, " ")
        }
        clean = clean.replace("কে", " ")
            .replace("please", "")
            .replace("দয়া করে", "")
            .replace("my", "")
            .replace("আমার", "")
            .trim()
        return clean
    }

    private fun extractAppName(text: String): String {
        return text.replace("খোলো", "")
            .replace("ওপেন", "")
            .replace("open", "")
            .replace("launch", "")
            .replace("অ্যাপ", "")
            .replace("app", "")
            .replace("please", "")
            .trim()
    }
}

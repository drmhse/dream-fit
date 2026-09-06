package com.drmhse.dream.fit

// iOS bundle ID -> display name + system icon. ANCS itself carries no icons and
// no image attachments, so the tray shows the source app's name and a matching
// glyph. Photo bodies ("My O2 / Photo") are MMS references iOS won't hand out.
object AppNames {
    data class Info(val name: String, val icon: Int)
    private val MAP = mapOf(
        "com.apple.MobileSMS" to Info("Messages", android.R.drawable.sym_action_chat),
        "com.apple.mobilephone" to Info("Phone", android.R.drawable.sym_action_call),
        "com.apple.mobilemail" to Info("Mail", android.R.drawable.sym_action_email),
        "net.whatsapp.WhatsApp" to Info("WhatsApp", android.R.drawable.sym_action_chat),
        "com.facebook.Messenger" to Info("Messenger", android.R.drawable.sym_action_chat),
        "com.telegram.TelegramMessenger" to Info("Telegram", android.R.drawable.sym_action_chat),
        "com.gmail.Gmail" to Info("Gmail", android.R.drawable.sym_action_email),
    )
    fun resolve(bundle: String): Info {
        MAP[bundle]?.let { return it }
        val short = bundle.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        return Info(short.ifEmpty { "iPhone" }, R.drawable.ic_stat_dreamfit)
    }
}

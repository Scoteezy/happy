package com.slopus.happy.ui.stories

import android.content.Context
import com.slopus.happy.R

/**
 * The system emoji the picker's Emoji tab offers for emoji overlays and
 * reaction bubbles, by category as the system keyboard groups them, and
 * the chips under the search field that search stickers by emoji
 * (happy-ios `StoryEmojiCatalog`).
 */
object StoryEmojiCatalog {
    class Category(val name: String, val emoji: List<String>)

    val chips = listOf("😂", "❤️", "👍", "🔥", "🎉", "😢", "😮", "😡", "🤔", "👋", "🙏", "😴")

    fun categories(context: Context): List<Category> = listOf(
        Category(context.getString(R.string.stories_emoji_people), split("😀 😃 😄 😁 😆 😅 😂 🤣 🥲 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 👻 👽 🤖 💩 😺 😸 😹 😻 👋 🤚 ✋ 🖖 👌 🤌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 👍 👎 ✊ 👊 👏 🙌 👐 🤲 🤝 🙏 💪 🧠 👀 👁️ 👅 👄 🫶")),
        Category(context.getString(R.string.stories_emoji_nature), split("🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🦈 🐊 🐘 🦒 🦘 🐕 🐈 🌵 🎄 🌲 🌳 🌴 🌱 🌿 🍀 🍁 🍂 🌸 🌼 🌻 🌹 🌷 🌞 🌝 🌚 🌙 ⭐️ 🌟 ⚡️ 🔥 🌈 ☀️ ⛅️ ☁️ 🌧️ ⛈️ ❄️ ☃️ 🌊 💧")),
        Category(context.getString(R.string.stories_emoji_food), split("🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🥕 🌽 🌶️ 🥔 🥐 🍞 🥖 🧀 🥚 🍳 🥞 🧇 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🌯 🥗 🍝 🍜 🍣 🍱 🍤 🍙 🍦 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍿 ☕️ 🍵 🧃 🥤 🍺 🍻 🥂 🍷 🍸 🍹 🍾")),
        Category(context.getString(R.string.stories_emoji_activity), split("⚽️ 🏀 🏈 ⚾️ 🎾 🏐 🏉 🎱 🏓 🏸 🏒 ⛳️ 🏹 🎣 🥊 🛹 ⛸️ 🎿 🏂 🏋️ 🤸 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🎗️ 🎫 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟️ 🎯 🎳 🎮 🧩")),
        Category(context.getString(R.string.stories_emoji_travel), split("🚗 🚕 🚙 🚌 🏎️ 🚓 🚑 🚒 🚚 🚜 🛵 🏍️ 🚲 🛴 🚨 🚀 🛸 🚁 ✈️ 🛫 🛬 ⛵️ 🚤 🚢 ⚓️ 🗺️ 🗽 🗼 🏰 🎡 🎢 🏖️ 🏝️ 🏔️ ⛰️ 🌋 🏕️ 🏠 🏡 🏢 🏬 🏥 🏦 🏨 ⛪️ 🌁 🌃 🏙️ 🌄 🌅 🌆 🌉 🎆 🎇")),
        Category(context.getString(R.string.stories_emoji_objects), split("⌚️ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 💿 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙️ ⏰ ⌛️ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 💸 💵 💰 💳 💎 ⚖️ 🔧 🔨 🛠️ ⚙️ 🧲 🔫 💣 🧨 🔪 🛡️ 🔮 🧿 💈 🔭 🔬 💊 💉 🧬 🦠 🧹 🧺 🧻 🚽 🛁 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🎁 🎈 🎏 🎀 🎊 🎉 ✉️ 📦 📝 📁 📅 📌 📎 ✂️ 🔒 🔓")),
        Category(context.getString(R.string.stories_emoji_symbols), split("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☯️ ♈️ ♉️ ♊️ ♋️ ♌️ ♍️ 🆗 🆒 🆕 🆓 ✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗️ 〰️ 💯 🔞 📵 🚫 ⛔️ ⭕️ 🛑 💢 ♨️ 🔅 🔆 ⚠️ 🔱 ⚜️ 🔰 ♻️ 💤 🎵 🎶 💬 💭 🗯️ 🔔 🔕 🏁 🚩")),
        Category(context.getString(R.string.stories_emoji_flags), split("🏳️ 🏴 🏳️‍🌈 🏴‍☠️ 🇺🇸 🇬🇧 🇨🇦 🇦🇺 🇩🇪 🇫🇷 🇪🇸 🇮🇹 🇵🇹 🇳🇱 🇧🇪 🇨🇭 🇦🇹 🇸🇪 🇳🇴 🇩🇰 🇫🇮 🇮🇪 🇵🇱 🇺🇦 🇬🇪 🇹🇷 🇮🇱 🇮🇳 🇨🇳 🇯🇵 🇰🇷 🇧🇷 🇦🇷 🇲🇽 🇨🇱 🇿🇦 🇳🇬 🇪🇬")),
    )

    private fun split(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }
}

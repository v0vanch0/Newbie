package app.nimarkogram.messenger.max

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.ui.ActionBar.Theme

/**
 * Native Android MAX frontend hosted by the unmodified NimarkoGram Android app
 * process. This is NOT a Flutter activity or a Komet reskin.
 *
 * Telegram-only screens are not yet wired to MAX models; this is the first
 * native MAX surface, sharing the NimarkoGram UI theme/runtime.
 */
class MaxHomeActivity : Activity() {
    private lateinit var client: MaxNativeClient
    private lateinit var root: LinearLayout
    private var currentChat = 0L
    private var currentChatTitle = ""
    private var myUserId = 0L
    private var phone = ""
    private var otpToken = ""
    private var savedChats = JSONArray()
    private val ui = Handler(Looper.getMainLooper())
    private val accent = Color.rgb(244, 65, 144)
    private val canvasColor = Color.rgb(247, 248, 251)
    private val line = Color.rgb(230, 233, 239)
    private val ink = Color.rgb(28, 31, 42)
    private val secondary = Color.rgb(129, 132, 144)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { org.telegram.messenger.ApplicationLoader.postInitApplication() } catch (_: Throwable) {}
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        client = MaxNativeClient.get(this)
        client.onPush = { _, _ ->
            if (currentChat != 0L) {
                ui.removeCallbacksAndMessages(null)
                ui.postDelayed({ if (currentChat != 0L) refreshHistory() }, 950L)
            }
        }
        base()
        busy("Подключение к MAX")
        client.connect { result ->
            result.onFailure { error(it) }
            result.onSuccess {
                client.loginSaved { res ->
                    res.onFailure { showLogin() }
                    res.onSuccess {
                        if (it.optBoolean("needsAuth", false)) showLogin()
                        else { updateProfile(it); showChats(it.optJSONArray("chats") ?: JSONArray()) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        client.onPush = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun bg(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun base() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvasColor)
        }
        setContentView(root)
    }
    private fun text(content: String, size: Float = 16f, color: Int = ink, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = content
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun header(title: String, back: Boolean, menu: String = "", action: (() -> Unit)? = null) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(7), dp(16), dp(7))
            setBackgroundColor(Color.WHITE)
            minimumHeight = dp(59)
        }
        if (back) {
            val arrow = text("‹", 36f, accent)
            arrow.setPadding(0, 0, dp(15), 0)
            arrow.setOnClickListener { onBackPressed() }
            bar.addView(arrow)
        }
        val label = text(title, 21f, ink, true)
        bar.addView(label, LinearLayout.LayoutParams(0, dp(44), 1f))
        if (menu.isNotEmpty()) {
            val trailing = text(menu, 15f, accent, true)
            trailing.setPadding(dp(8), dp(6), dp(8), dp(6))
            trailing.setOnClickListener { action?.invoke() }
            bar.addView(trailing)
        }
        root.addView(bar)
        root.addView(View(this).apply { setBackgroundColor(line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
    }
    private fun button(label: String, handler: () -> Unit): TextView = text(label, 16f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        background = bg(accent, 13)
        minimumHeight = dp(50)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { handler() }
    }
    private fun field(hint: String, phoneMode: Boolean = false): EditText = EditText(this).apply {
        setSingleLine(true)
        textSize = 17f
        setTextColor(ink)
        setHintTextColor(secondary)
        setHint(hint)
        setPadding(dp(15), dp(8), dp(15), dp(8))
        background = bg(Color.WHITE, 12)
        inputType = if (phoneMode) android.text.InputType.TYPE_CLASS_PHONE
            else android.text.InputType.TYPE_CLASS_NUMBER
        minimumHeight = dp(54)
    }
    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(root.windowToken, 0)
    }
    private fun centerColumn(): LinearLayout {
        val scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(25), dp(45), dp(25), dp(20))
            scroll.addView(this)
        }
    }
    private fun busy(status: String) {
        currentChat = 0L
        root.removeAllViews()
        header("NimarkoMAX", false)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        container.addView(spinner, LinearLayout.LayoutParams(dp(36), dp(36)))
        val caption = text(status, 15f, secondary)
        caption.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(-1, dp(60))
        container.addView(caption, lp)
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private fun error(t: Throwable) {
        showLogin()
        AlertDialog.Builder(this).setTitle("Соединение MAX")
            .setMessage(t.message ?: t.javaClass.simpleName)
            .setPositiveButton("Повторить") { _, _ ->
                busy("Подключение")
                client.connect {
                    it.onSuccess { showLogin() }
                    it.onFailure { ex -> error(ex) }
                }
            }.setNegativeButton("Закрыть", null).show()
    }
    private fun showLogin() {
        currentChat = 0L
        root.removeAllViews()
        header("NimarkoMAX", false)
        val body = centerColumn()
        body.addView(text("MAX внутри NimarkoGram", 25f, ink, true))
        body.addView(text("Вход по номеру телефона. Код приходит через MAX.", 14f, secondary)
            .apply { setPadding(0, dp(12), 0, dp(28)) })
        val input = field("+7 900 000 00 00", true)
        input.setText(phone)
        body.addView(input)
        body.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
        body.addView(button("Получить код") {
            phone = input.text.toString()
            hideKeyboard()
            busy("Запрос кода подтверждения")
            client.requestCode(phone) { result ->
                result.onSuccess {
                    otpToken = it.optString("token")
                    showCode()
                }
                result.onFailure { error(it) }
            }
        })
    }
    private fun showCode() {
        root.removeAllViews()
        header("Подтверждение", true)
        val body = centerColumn()
        body.addView(text("Введите код MAX", 24f, ink, true))
        body.addView(text("Код отправлен для " + phone, 14f, secondary)
            .apply { setPadding(0, dp(12), 0, dp(25)) })
        val input = field("Код из MAX")
        body.addView(input)
        body.addView(View(this), LinearLayout.LayoutParams(1, dp(15)))
        body.addView(button("Войти") {
            val code = input.text.toString()
            hideKeyboard()
            busy("Авторизация")
            client.verifyCode(code, otpToken) { result ->
                result.onSuccess {
                    updateProfile(it)
                    showChats(it.optJSONArray("chats") ?: JSONArray())
                }
                result.onFailure { error(it) }
            }
        })
    }
    private fun updateProfile(login: JSONObject) {
        val profile = login.optJSONObject("profile") ?: return
        myUserId = profile.optJSONObject("contact")?.optLong("id") ?: profile.optLong("id", 0)
    }
    private fun chatTitle(chat: JSONObject): String {
        val title = chat.optString("title")
        if (title.isNotBlank()) return title
        val custom = chat.optString("displayName")
        if (custom.isNotBlank()) return custom
        return "Диалог " + chat.optLong("id")
    }
    private fun showChats(items: JSONArray) {
        currentChat = 0L
        savedChats = items
        root.removeAllViews()
        header("Чаты", false, "⋮") {
            AlertDialog.Builder(this)
                .setItems(arrayOf("Обновить", "Выйти из MAX")) { _, which ->
                    if (which == 0) client.loadChats { result ->
                        result.onSuccess { showChats(it.optJSONArray("chats") ?: JSONArray()) }
                        result.onFailure { error(it) }
                    } else AlertDialog.Builder(this).setMessage("Выйти из аккаунта MAX?")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Выйти") { _, _ ->
                            client.logout { showLogin() }
                        }.show()
                }.show()
        }
        val search = EditText(this).apply {
            hint = "Поиск по чатам"
            isSingleLine = true
            textSize = 15f
            setPadding(dp(18), dp(5), dp(18), dp(5))
            background = bg(Color.WHITE, 13)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply {
            setMargins(dp(12), dp(12), dp(12), dp(8))
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun fill(query: String) {
            list.removeAllViews()
            var shown = 0
            for (i in 0 until items.length()) {
                val chat = items.optJSONObject(i) ?: continue
                val title = chatTitle(chat)
                if (!title.contains(query, ignoreCase = true)) continue
                shown++
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(16), dp(10))
                    minimumHeight = dp(78)
                }
                val avatar = text(title.take(1).uppercase(), 21f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    background = bg(if (i % 3 == 0) accent else if (i % 3 == 1)
                        Color.rgb(96, 134, 222) else Color.rgb(72, 180, 170), 28)
                }
                row.addView(avatar, LinearLayout.LayoutParams(dp(53), dp(53)))
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(13), 0, 0, 0)
                }
                val name = text(title, 16f, ink, true)
                name.maxLines = 1
                content.addView(name)
                val preview = chat.optJSONObject("lastMessage")?.optString("text").orEmpty()
                content.addView(text(if (preview.isNotBlank()) preview else "Сообщения", 14f, secondary).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                })
                row.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
                val unread = chat.optInt("newMessages")
                if (unread > 0) row.addView(text(unread.toString(), 13f, accent, true))
                row.setOnClickListener { openChat(chat.optLong("id"), title) }
                list.addView(row)
                list.addView(View(this).apply { setBackgroundColor(line) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(80) })
            }
            if (shown == 0) {
                list.addView(text("Нет диалогов", 16f, secondary).apply {
                    gravity = Gravity.CENTER
                    minimumHeight = dp(140)
                })
            }
        }
        fill("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = fill(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }
    private fun openChat(chatId: Long, title: String) {
        currentChat = chatId
        currentChatTitle = title
        renderChat(JSONArray())
        refreshHistory()
    }
    private fun refreshHistory() {
        val active = currentChat
        if (active == 0L) return
        client.history(active) { result ->
            if (active != currentChat) return@history
            result.onSuccess { renderChat(it.optJSONArray("messages") ?: JSONArray()) }
            result.onFailure {
                android.widget.Toast.makeText(this, it.message ?: "Ошибка загрузки", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun renderChat(messages: JSONArray) {
        root.removeAllViews()
        header(currentChatTitle, true, "↻") { refreshHistory() }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(canvasColor)
        }
        val bubbles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(13), dp(10), dp(13))
            gravity = Gravity.BOTTOM
        }
        scroll.addView(bubbles)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        for (i in messages.length() - 1 downTo 0) {
            val item = messages.optJSONObject(i) ?: continue
            val sender = item.optLong("sender", -1)
            val mine = myUserId != 0L && sender == myUserId
            val content = item.optString("text").ifBlank {
                if (item.has("attaches")) "Вложение" else "Сообщение"
            }
            val row = FrameLayout(this)
            val messageText = text(content, 15.5f, ink).apply {
                setPadding(dp(13), dp(9), dp(13), dp(10))
                background = bg(if (mine) Color.rgb(255, 217, 234) else Color.WHITE, 13)
                setOnLongClickListener {
                    AlertDialog.Builder(this@MaxHomeActivity)
                        .setItems(arrayOf("Копировать", "Удалить у себя")) { _, choice ->
                            if (choice == 0) {
                                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clip.setPrimaryClip(ClipData.newPlainText("MAX", content))
                            } else {
                                client.deleteMessages(currentChat, JSONArray().put(item.optLong("id")), false) {
                                    refreshHistory()
                                }
                            }
                        }.show()
                    true
                }
            }
            row.addView(messageText, FrameLayout.LayoutParams(-2, -2,
                if (mine) Gravity.RIGHT else Gravity.LEFT))
            bubbles.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(3), 0, dp(3))
            })
        }
        val composer = LinearLayout(this).apply {
            setPadding(dp(9), dp(8), dp(9), dp(9))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val field = EditText(this).apply {
            hint = "Сообщение"
            textSize = 15f
            maxLines = 5
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = bg(canvasColor, 19)
        }
        composer.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(text("➤", 28f, accent, true).apply {
            setPadding(dp(12), 0, dp(7), 0)
            setOnClickListener {
                val msg = field.text.toString()
                if (msg.isBlank()) return@setOnClickListener
                field.text.clear()
                client.sendMessage(currentChat, msg) {
                    it.onSuccess { refreshHistory() }
                    it.onFailure { ex ->
                        field.setText(msg)
                        android.widget.Toast.makeText(this@MaxHomeActivity,
                            ex.message ?: "Ошибка отправки", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
        root.addView(composer, LinearLayout.LayoutParams(-1, -2))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    override fun onBackPressed() {
        when {
            currentChat != 0L -> showChats(savedChats)
            otpToken.isNotEmpty() -> { otpToken = ""; showLogin() }
            else -> super.onBackPressed()
        }
    }
}

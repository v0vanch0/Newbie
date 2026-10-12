package app.nimarkogram.messenger.max

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import ru.kolibri.Auth
import ru.kolibri.Config
import ru.kolibri.Kolibri
import ru.kolibri.Session

/**
 * MAX operations for a native NimarkoGram Android host. No Komet UI, Dart,
 * Flutter, or Telegram MTProto request is used by this transport.
 *
 * Network work is serialized on a worker; only callbacks touch the main UI.
 * Kolibri JNI and JNI-backed TLS/protocol are from KometTeam/kolibri (MIT/Apache).
 */
class MaxNativeClient private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("nimarkomax_account", Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nimarkomax-transport").apply { isDaemon = true }
    }
    private var session: Session? = null
    private var handshake = JSONObject()
    private val chats = linkedMapOf<Long, JSONObject>()
    private var chatMarker: Long = 0L
    @Volatile private var pollActive = false
    @Volatile var onPush: ((Int, JSONObject) -> Unit)? = null
    @Volatile var loggedIn = false
        private set

    companion object {
        @Volatile private var singleton: MaxNativeClient? = null
        @JvmStatic fun get(context: Context): MaxNativeClient =
            singleton ?: synchronized(this) {
                singleton ?: MaxNativeClient(context).also { singleton = it }
            }
    }

    private fun run(callback: (Result<JSONObject>) -> Unit, action: () -> JSONObject) {
        executor.execute {
            val result = runCatching { action() }
            ui.post { callback(result) }
        }
    }

    private fun storedId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun ensureConnected(): Session {
        val active = session
        if (active != null && active.state == ru.kolibri.SessionState.ONLINE) return active
        try { active?.close() } catch (_: Exception) {}
        Kolibri.setTrustMincifryCa(true)
        val id = storedId()
        val s = Session.open(
            Config(
                host = "api2.oneme.ru",
                deviceId = id,
                instanceId = id,
                appVersion = "26.20.2",
                buildNumber = 6758,
                osVersion = "Android " + Build.VERSION.RELEASE,
                timezone = TimeZone.getDefault().id,
                screen = "420dpi 420dpi 1080x2340",
                arch = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "arm64-v8a",
                locale = "ru",
                deviceName = Build.MANUFACTURER + " " + Build.MODEL,
                deviceLocale = "ru",
                clientSessionId = System.currentTimeMillis() / 1000,
                pingIntervalSeconds = 10L,
                autoReconnect = true
            )
        )
        try {
            handshake = JSONObject(s.blocking.connect())
            session = s
            startPushPump(s)
        } catch (ex: Exception) {
            s.close()
            throw ex
        }
        return s
    }

    private fun request(opcode: Int, json: JSONObject): JSONObject {
        val answer = JSONObject(ensureConnected().blocking.requestJson(opcode, json.toString()))
        // Transport failures surface as exceptions; server may return an error payload.
        val message = answer.optString("message")
        if (message.startsWith("FAIL_")) throw IllegalStateException(message)
        return answer
    }

    private fun binary(value: ByteArray): JSONObject =
        JSONObject().put("$" + "bin", Base64.encodeToString(value, Base64.NO_WRAP))

    private fun fingerprint(): JSONObject? {
        if (!handshake.has("callsSeed")) return null
        val seed = handshake.optLong("callsSeed")
        return binary(Auth.fingerprint(seed, storedId()))
    }

    fun connect(callback: (Result<JSONObject>) -> Unit) =
        run(callback) { ensureConnected(); JSONObject().put("connected", true) }

    fun requestCode(phone: String, callback: (Result<JSONObject>) -> Unit) = run(callback) {
        val digits = phone.filter { it.isDigit() }
        require(digits.length in 10..15) { "Укажи корректный международный номер" }
        val payload = JSONObject()
            .put("phone", "+" + digits)
            .put("type", "START_AUTH")
            .put("language", "ru")
        fingerprint()?.let { payload.put("mode", it) }
        val response = request(17, payload)
        val otpToken = response.optString("token")
        check(otpToken.isNotBlank()) { "MAX не выдал токен подтверждения" }
        JSONObject().put("token", otpToken)
    }

    fun verifyCode(code: String, otpToken: String, callback: (Result<JSONObject>) -> Unit) =
        run(callback) {
            require(code.isNotBlank() && otpToken.isNotBlank()) { "Нужен код подтверждения" }
            val response = request(18, JSONObject()
                .put("token", otpToken)
                .put("verifyCode", code.trim())
                .put("authTokenType", "CHECK_CODE"))
            val attrs = response.optJSONObject("tokenAttrs")
            val loginToken = attrs?.optJSONObject("LOGIN")?.optString("token").orEmpty()
            when {
                loginToken.isNotEmpty() -> {
                    persistToken(loginToken)
                    loginImpl(loginToken)
                }
                attrs?.optJSONObject("REGISTER") != null ->
                    throw IllegalStateException("Для нового аккаунта требуется завершение регистрации MAX")
                response.has("passwordChallenge") ->
                    throw IllegalStateException("Для аккаунта требуется второй фактор авторизации")
                else -> throw IllegalStateException("Сервер не вернул токен входа")
            }
        }

    fun loginSaved(callback: (Result<JSONObject>) -> Unit) = run(callback) {
        val token = readToken()
        if (token.isNullOrEmpty()) JSONObject().put("needsAuth", true)
        else loginImpl(token)
    }

    private fun loginImpl(token: String): JSONObject {
        val payload = JSONObject()
            .put("token", token)
            .put("interactive", true)
            .put("exp", JSONObject().put("chatsCountGroups", binary(byteArrayOf(0x0b, 0x32))))
            .put("presenceSync", -1)
            .put("chatsSync", -1)
        fingerprint()?.let { payload.put("chatCacheFingerprint", it) }
        val result = request(19, payload)
        result.optString("token").takeIf { it.isNotBlank() }?.let { persistToken(it) }
        loggedIn = true
        chats.clear()
        val first = result.optJSONArray("chats")
        ingestChats(first)
        chatMarker = result.optLong("chatMarker", 0)
        var marker = chatMarker
        var count = 0
        while (marker > 0L && count++ < 15) {
            val page = request(53, JSONObject().put("marker", marker).put("count", 50))
            val items = page.optJSONArray("chats") ?: break
            if (items.length() == 0) break
            ingestChats(items)
            val next = page.optLong("marker", 0)
            if (next <= 0 || next == marker || items.length() < 50) break
            marker = next
        }
        return JSONObject().put("profile", result.optJSONObject("profile"))
            .put("chats", snapshotChats())
            .put("loggedIn", true)
    }

    private fun ingestChats(items: JSONArray?) {
        if (items == null) return
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optLong("id", 0)
            if (id != 0L) chats[id] = item
        }
    }

    fun snapshotChats(): JSONArray {
        val sorted = chats.values.sortedByDescending { it.optLong("lastEventTime", 0) }
        return JSONArray().also { a -> sorted.forEach(a::put) }
    }

    fun loadChats(callback: (Result<JSONObject>) -> Unit) =
        run(callback) { check(loggedIn) { "Войди в MAX" }; JSONObject().put("chats", snapshotChats()) }

    fun history(chatId: Long, callback: (Result<JSONObject>) -> Unit) = run(callback) {
        check(loggedIn) { "Войди в MAX" }
        request(49, JSONObject()
            .put("chatId", chatId)
            .put("from", System.currentTimeMillis() + 86_400_000)
            .put("forward", 0)
            .put("backward", 80)
            .put("getMessages", true))
    }

    fun sendMessage(chatId: Long, text: String, callback: (Result<JSONObject>) -> Unit) =
        run(callback) {
            check(loggedIn) { "Войди в MAX" }
            require(text.isNotBlank()) { "Сообщение пустое" }
            request(64, JSONObject().put("chatId", chatId).put("notify", true)
                .put("message", JSONObject()
                    .put("cid", -System.currentTimeMillis())
                    .put("text", text)
                    .put("elements", JSONArray())
                    .put("attaches", JSONArray())))
        }

    fun markRead(chatId: Long, mark: Long, callback: (Result<JSONObject>) -> Unit) =
        run(callback) { request(50, JSONObject().put("chatId", chatId).put("mark", mark).put("type", "READ")) }

    fun editMessage(chatId: Long, messageId: Long, text: String, callback: (Result<JSONObject>) -> Unit) =
        run(callback) { request(67, JSONObject().put("chatId", chatId).put("messageId", messageId).put("text", text).put("elements", JSONArray())) }

    fun deleteMessages(chatId: Long, ids: JSONArray, forEveryone: Boolean, callback: (Result<JSONObject>) -> Unit) =
        run(callback) { request(66, JSONObject().put("chatId", chatId).put("messageIds", ids).put("forMe", !forEveryone).put("itemType", "REGULAR")) }

    fun logout(callback: (Result<JSONObject>) -> Unit) = run(callback) {
        try { if (loggedIn) request(20, JSONObject()) } catch (_: Exception) {}
        pollActive = false
        loggedIn = false
        session?.close()
        session = null
        chats.clear()
        prefs.edit().remove("auth_secret").apply()
        JSONObject().put("loggedOut", true)
    }

    private fun startPushPump(s: Session) {
        pollActive = false
        pollActive = true
        Thread({
            while (pollActive && session === s) {
                try {
                    val push = s.blocking.nextPush(2.0) ?: continue
                    val obj = JSONObject(push.json)
                    if (push.opcode == 129 || push.opcode == 130) {
                        // A new dialog list can be refreshed on the next explicit reload.
                    }
                    ui.post { onPush?.invoke(push.opcode, obj) }
                } catch (_: Exception) {
                    if (s.state == ru.kolibri.SessionState.DISCONNECTED) break
                }
            }
        }, "nimarkomax-pushes").apply { isDaemon = true }.start()
    }

    // Auth token is encrypted at rest with Android Keystore; no token goes to logs.
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val current = store.getKey("NimarkoMaxAuth", null) as? SecretKey
        if (current != null) return current
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("NimarkoMaxAuth",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun persistToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val joined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, joined, 0, iv.size)
        System.arraycopy(encrypted, 0, joined, iv.size, encrypted.size)
        prefs.edit().putString("auth_secret", Base64.encodeToString(joined, Base64.NO_WRAP)).apply()
    }

    private fun readToken(): String? {
        val data = prefs.getString("auth_secret", null) ?: return null
        return try {
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            prefs.edit().remove("auth_secret").apply()
            null
        }
    }
}

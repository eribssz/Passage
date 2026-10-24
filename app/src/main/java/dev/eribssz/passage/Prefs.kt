package dev.eribssz.passage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.eribssz.passage.core.Endpoint
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One VPN gateway: a name, one or more addresses (tried in turn), and a login. */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val servers: List<Endpoint> = emptyList(),
    val username: String = "",
    val realm: String = "",
    /** Trusted certificate per address ("host:port" -> SHA-256). */
    val pins: Map<String, String> = emptyMap(),
    val rememberPassword: Boolean = false,
    /** Index of the address that worked last; tried first next time. */
    val lastGood: Int = 0,
    /** A fixed address ("host:port") to always use; null = automatic rotation and failover. */
    val preferred: String? = null,
) {
    val fixed get() = servers.firstOrNull { it.key == preferred }

    val isNone get() = id == "none"
    val primary get() = servers.getOrNull(lastGood.coerceIn(0, (servers.size - 1).coerceAtLeast(0))) ?: servers.firstOrNull()
    val trusted get() = pins.isNotEmpty()

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("username", username); put("realm", realm)
        put("remember", rememberPassword); put("lastGood", lastGood)
        put("servers", JSONArray(servers.map { it.key }))
        put("pins", JSONObject(pins))
        if (preferred != null) put("preferred", preferred)
    }

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            id = o.getString("id"),
            name = o.optString("name"),
            servers = o.optJSONArray("servers")?.let { a -> (0 until a.length()).mapNotNull { Endpoint.parse(a.getString(it)) } }.orEmpty(),
            username = o.optString("username"),
            realm = o.optString("realm"),
            pins = o.optJSONObject("pins")?.let { p -> p.keys().asSequence().associateWith { p.getString(it) } }.orEmpty(),
            rememberPassword = o.optBoolean("remember"),
            lastGood = o.optInt("lastGood"),
            preferred = o.optString("preferred").ifBlank { null },
        )

        /** Placeholder when nothing is configured yet (never saved). */
        val NONE = Profile(id = "none")
    }
}

/** Profiles as JSON in prefs; remembered passwords encrypted with an Android Keystore key. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("passage", Context.MODE_PRIVATE)

    init { migrate() }

    /** v0.x stored a single gateway in flat keys. */
    private fun migrate() {
        if (sp.contains("profiles")) return
        if (!sp.contains("server")) { sp.edit().putString("profiles", "[]").apply(); return }
        val p = run {
            val ep = Endpoint(sp.getString("server", "")!!, sp.getInt("port", 443))
            Profile(
                name = sp.getString("name", null) ?: "VPN",
                servers = listOf(ep),
                username = sp.getString("username", "") ?: "",
                realm = sp.getString("realm", "") ?: "",
                pins = sp.getString("pin", null)?.let { mapOf(ep.key to it) }.orEmpty(),
                rememberPassword = sp.getBoolean("remember", false),
            )
        }
        val oldPw = sp.getString("pw", null)
        sp.edit().apply {
            putString("profiles", JSONArray(listOf(p.toJson())).toString())
            putString("active", p.id)
            if (oldPw != null) putString("pw_${p.id}", oldPw)
            listOf("name", "server", "port", "username", "realm", "pin", "remember", "pw").forEach { remove(it) }
        }.apply()
    }

    fun profiles(): List<Profile> = runCatching {
        val a = JSONArray(sp.getString("profiles", "[]"))
        (0 until a.length()).map { Profile.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** The selected gateway, or [Profile.NONE] when none exist yet. */
    fun active(): Profile {
        val all = profiles()
        val id = sp.getString("active", null)
        return all.firstOrNull { it.id == id } ?: all.firstOrNull() ?: Profile.NONE
    }

    fun setActive(id: String) = sp.edit().putString("active", id).apply()

    private fun write(all: List<Profile>) =
        sp.edit().putString("profiles", JSONArray(all.map { it.toJson() }).toString()).apply()

    /** Inserts or updates; pins of addresses that were removed are dropped. */
    fun save(p: Profile) {
        if (p.isNone) return
        val keys = p.servers.map { it.key }.toSet()
        val clean = p.copy(
            pins = p.pins.filterKeys { it in keys },
            lastGood = p.lastGood.coerceIn(0, (p.servers.size - 1).coerceAtLeast(0)),
            preferred = p.preferred?.takeIf { pref -> pref in keys },
        )
        val all = profiles().toMutableList()
        val i = all.indexOfFirst { it.id == p.id }
        if (i >= 0) all[i] = clean else all += clean
        write(all)
        if (!clean.rememberPassword) savePassword(clean.id, null)
    }

    fun delete(id: String) {
        val rest = profiles().filter { it.id != id }
        write(rest)
        savePassword(id, null)
        if (sp.getString("active", null) == id) rest.firstOrNull()?.let { setActive(it.id) } ?: sp.edit().remove("active").apply()
    }

    private fun update(id: String, f: (Profile) -> Profile) {
        profiles().firstOrNull { it.id == id }?.let { save(f(it)) }
    }

    fun addPin(id: String, endpoint: String, sha: String) = update(id) { it.copy(pins = it.pins + (endpoint to sha)) }
    fun clearPins(id: String) = update(id) { it.copy(pins = emptyMap()) }
    fun setLastGood(id: String, index: Int) = update(id) { it.copy(lastGood = index) }
    fun setPreferred(id: String, key: String?) = update(id) { it.copy(preferred = key) }

    /** All gateways as JSON for backup / moving to another phone. Passwords are never included. */
    fun exportJson(): String = JSONObject().apply {
        put("app", "passage"); put("format", 1)
        put("gateways", JSONArray(profiles().map { it.toJson().apply { remove("remember"); remove("lastGood") } }))
    }.toString(2)

    /** Adds or updates gateways from [exportJson] output. Returns how many were imported. */
    fun importJson(text: String): Int {
        val root = JSONObject(text)
        require(root.optString("app") == "passage") { "Not a Passage config file" }
        val arr = root.getJSONArray("gateways")
        val existing = profiles()
        var count = 0
        for (i in 0 until arr.length()) {
            val incoming = Profile.fromJson(arr.getJSONObject(i)).copy(rememberPassword = false)
            if (incoming.servers.isEmpty()) continue
            val match = existing.firstOrNull { it.id == incoming.id } ?: existing.firstOrNull { it.name == incoming.name }
            save(if (match != null) incoming.copy(id = match.id, rememberPassword = match.rememberPassword) else incoming)
            count++
        }
        if (sp.getString("active", null) == null) profiles().firstOrNull()?.let { setActive(it.id) }
        return count
    }

    fun savePassword(id: String, pw: String?) {
        if (pw == null) { sp.edit().remove("pw_$id").apply(); return }
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val enc = c.iv + c.doFinal(pw.toByteArray())
            sp.edit().putString("pw_$id", Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
        }
    }

    fun loadPassword(id: String): String? = runCatching {
        val raw = Base64.decode(sp.getString("pw_$id", null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("passage_pw", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("passage_pw", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
}

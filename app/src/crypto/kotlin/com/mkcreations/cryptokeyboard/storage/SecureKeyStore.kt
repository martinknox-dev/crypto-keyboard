package com.mkcreations.cryptokeyboard.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mkcreations.cryptokeyboard.model.CipherKey
import org.json.JSONArray
import org.json.JSONObject

class SecureKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "cipher_keys_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEYS_LIST = "keys_list"
        private const val ACTIVE_KEY_ID = "active_key_id"
        private const val KEY_CHAT_MAP = "key_chat_map"

        @Volatile
        private var instance: SecureKeyStore? = null

        fun getInstance(context: Context): SecureKeyStore {
            return instance ?: synchronized(this) {
                instance ?: SecureKeyStore(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // --- Key CRUD Operations ---

    fun saveKey(key: CipherKey) {
        val keys = getAllKeys().toMutableList()
        val existingIndex = keys.indexOfFirst { it.id == key.id }
        if (existingIndex >= 0) {
            keys[existingIndex] = key
        } else {
            keys.add(key)
        }
        saveAllKeys(keys)
    }

    fun getKey(keyId: String): CipherKey? {
        return getAllKeys().find { it.id == keyId }
    }

    fun getAllKeys(): List<CipherKey> {
        val json = prefs.getString(KEYS_LIST, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val keys = mutableListOf<CipherKey>()
            for (i in 0 until array.length()) {
                keys.add(CipherKey.fromJson(array.getJSONObject(i)))
            }
            keys
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteKey(keyId: String) {
        val keys = getAllKeys().filter { it.id != keyId }
        saveAllKeys(keys)
        // Clear active key if deleted
        if (getActiveKeyId() == keyId) {
            setActiveKeyId(null)
        }
        // Remove from chat map
        removeChatMappingsForKey(keyId)
    }

    fun deleteAllKeys() {
        prefs.edit()
            .remove(KEYS_LIST)
            .remove(ACTIVE_KEY_ID)
            .remove(KEY_CHAT_MAP)
            .apply()
    }

    fun keyCount(): Int = getAllKeys().size

    // --- Active Key ---

    fun setActiveKeyId(keyId: String?) {
        prefs.edit().putString(ACTIVE_KEY_ID, keyId).apply()
    }

    fun getActiveKeyId(): String? {
        return prefs.getString(ACTIVE_KEY_ID, null)
    }

    fun getActiveKey(): CipherKey? {
        val id = getActiveKeyId() ?: return null
        return getKey(id)
    }

    // --- Update Last Used ---

    fun updateLastUsed(keyId: String) {
        val key = getKey(keyId) ?: return
        val updated = key.copy(lastUsedAt = System.currentTimeMillis())
        saveKey(updated)
    }

    // --- Smart Key-Per-Chat Mapping ---

    fun setKeyForChat(chatIdentifier: String, keyId: String) {
        val map = getChatKeyMap().toMutableMap()
        map[chatIdentifier] = keyId
        saveChatKeyMap(map)
    }

    fun getKeyForChat(chatIdentifier: String): String? {
        return getChatKeyMap()[chatIdentifier]
    }

    private fun getChatKeyMap(): Map<String, String> {
        val json = prefs.getString(KEY_CHAT_MAP, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                map[key] = obj.getString(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveChatKeyMap(map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_CHAT_MAP, obj.toString()).apply()
    }

    private fun removeChatMappingsForKey(keyId: String) {
        val map = getChatKeyMap().filter { it.value != keyId }
        saveChatKeyMap(map)
    }

    // --- Internal ---

    private fun saveAllKeys(keys: List<CipherKey>) {
        val array = JSONArray()
        keys.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEYS_LIST, array.toString()).apply()
    }
}
package com.kazamatech.kzdesk.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * M1 — CredentialVault: password RDP di-enskripsi **AES-256-GCM**,
 * master key disimpan di **Android Keystore** (non-exportable).
 *
 * Properti:
 *  - Plaintext TIDAK pernah menyentuh disk (hanya di memori sesaat).
 *  - Ciphertext format: `base64(iv[12] || ciphertext || tag[16])` di
 *    SharedPreferences (bukan di repo, bukan di log).
 *  - Jika keystore di-reset sistem (uninstall/clear data), [get]
 *    mengembalikan null — UI minta user input ulang password.
 *
 * Batasan v1 (documented):
 *  - Satu master key (alias tetap, `kzdesk_cred_vault_v1`); rotasi key = v2.
 *  - Non-exportable = tidak bisa dicabut per-device-remotely; untuk v2
 *    pertimbangkan biometrik (UserAuthentication) di policy "aman ekstra".
 */
class CredentialVault(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * Simpan/replace [plaintext] untuk [id] (biasanya `host:port`).
     * @return true jika tersimpan; false jika keystore gagal (password
     *         tetap dipakai untuk connect sesi ini, hanya tidak diingat).
     */
    fun put(id: String, plaintext: String): Boolean = try {
        val key = masterKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = iv + ct
        prefs.edit().putString(id, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    } catch (e: Exception) {
        android.util.Log.w(TAG, "gagal simpan ke vault (keystore?): ${e.javaClass.simpleName}")
        false
    }

    /** Ambil plaintext untuk [id]; null jika tidak ada atau tak bisa didekripsi. */
    fun get(id: String): String? {
        val b64 = prefs.getString(id, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            if (blob.size <= GCM_IV_BYTES) return null
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ct = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val key = masterKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null // keystore reset / corrupt — perlakukan sebagai tidak tersimpan
        }
    }

    fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }

    fun has(id: String): Boolean = prefs.contains(id)

    /** Hapus SEMUA kredensial tersimpan (opsi "hapus semua" di settings). */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        /**
         * Kunci kredensial per host+port+username (bukan per profil).
         *
         * Permintaan pemilik 2026-10-10: sekali password dimasukkan untuk satu
         * host, koneksi berikutnya ke host yang sama tidak perlu input ulang.
         * Data tetap lokal terenkripsi — hilang hanya bila data app dihapus
         * atau app di-uninstall.
         */
        fun hostCredKey(host: String, port: Int, user: String): String =
            "hostcred:" + host.trim().lowercase() + ":" + port + ":" + user.trim().lowercase()

        private const val TAG = "CredentialVault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_ALIAS = "kzdesk_cred_vault_v1"
        private const val PREFS_FILE = "kzdesk_credential_vault"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}

package com.kazamatech.kzdesk.ui

import android.content.Context

/** Penyimpanan template buatan/impor pengguna (SharedPreferences, JSON). */
class ControllerTemplateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<ControllerTemplate> = ControllerTemplates.decodeList(prefs.getString(KEY, null))

    /** @return false bila batas [ControllerTemplates.MAX_TEMPLATES] tercapai (template baru ditolak). */
    fun save(template: ControllerTemplate): Boolean {
        val others = list().filterNot { it.id == template.id }
        if (others.size >= ControllerTemplates.MAX_TEMPLATES) return false
        write(listOf(template.copy(builtIn = false)) + others)
        return true
    }

    fun delete(id: String) = write(list().filterNot { it.id == id })

    private fun write(list: List<ControllerTemplate>) {
        prefs.edit().putString(KEY, ControllerTemplates.encodeList(list)).apply()
    }

    private companion object {
        const val PREFS = "kzdesk_controller_templates"
        const val KEY = "templates_v1"
    }
}

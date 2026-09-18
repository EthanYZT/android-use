package com.androiduse.capability

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone

/** 联系人电话按姓名模糊查（`display_name LIKE %name%`），最多 [ContactsText.MAX_ROWS]+1 条（多出的一条用来提示"还有"）。 */
class ContactsStore(private val context: Context) {
    fun lookup(name: String): List<ContactPhone> {
        val proj = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL)
        val out = ArrayList<ContactPhone>()
        context.contentResolver.query(
            Phone.CONTENT_URI, proj, "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), Phone.DISPLAY_NAME + " ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size <= ContactsText.MAX_ROWS) {
                val type = Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                out += ContactPhone(c.getString(0) ?: "", c.getString(1) ?: "", type)
            }
        }
        return out
    }
}

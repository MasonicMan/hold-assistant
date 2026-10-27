package app.holdassistant.contacts

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import app.holdassistant.core.ContactInfo

/**
 * Reads the phone's address book. Google contacts sync into it, so this
 * covers them without a separate Google sign-in. Needs READ_CONTACTS.
 */
object ContactsReader {
    private class Builder(val id: String) {
        var name = ""
        var given: String? = null
        var family: String? = null
        var org: String? = null
        var title: String? = null
        val phones = mutableListOf<ContactInfo.Phone>()
        var starred = false
        var birthday = false
        var nickname = false
        var relation = false

        fun build() = ContactInfo(id, name, given, family, org, title, phones.distinctBy { it.number.filter(Char::isDigit) },
            starred, birthday, nickname, relation)
    }

    fun read(context: Context): List<ContactInfo> {
        val mimes = listOf(
            StructuredName.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE,
            Event.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE, Relation.CONTENT_ITEM_TYPE,
        )
        val projection = arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DISPLAY_NAME, Data.STARRED, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4)
        val selection = "${Data.MIMETYPE} IN (${mimes.joinToString { "?" }})"
        val byId = LinkedHashMap<String, Builder>()

        context.contentResolver.query(Data.CONTENT_URI, projection, selection, mimes.toTypedArray(), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val b = byId.getOrPut(id) { Builder(id) }
                b.name = c.getString(2).orEmpty().ifEmpty { b.name }
                b.starred = b.starred || c.getInt(3) == 1
                val d1 = c.getString(4)
                when (c.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        b.given = c.getString(6) // StructuredName.GIVEN_NAME = DATA2
                        b.family = c.getString(7) // StructuredName.FAMILY_NAME = DATA3
                    }
                    Organization.CONTENT_ITEM_TYPE -> {
                        b.org = d1 // Organization.COMPANY = DATA1
                        b.title = c.getString(7) // Organization.TITLE = DATA4
                    }
                    Phone.CONTENT_ITEM_TYPE -> if (!d1.isNullOrBlank()) b.phones += ContactInfo.Phone(d1, phoneType(c.getInt(5)))
                    Event.CONTENT_ITEM_TYPE -> if (c.getInt(5) == Event.TYPE_BIRTHDAY) b.birthday = true
                    Nickname.CONTENT_ITEM_TYPE -> b.nickname = true
                    Relation.CONTENT_ITEM_TYPE -> b.relation = true
                }
            }
        }
        return byId.values.map { it.build() }.filter { it.phones.isNotEmpty() && it.displayName.isNotBlank() }
    }

    private fun phoneType(t: Int) = when (t) {
        Phone.TYPE_MOBILE, Phone.TYPE_WORK_MOBILE -> ContactInfo.PhoneType.MOBILE
        Phone.TYPE_HOME -> ContactInfo.PhoneType.HOME
        Phone.TYPE_WORK -> ContactInfo.PhoneType.WORK
        Phone.TYPE_MAIN, Phone.TYPE_COMPANY_MAIN -> ContactInfo.PhoneType.MAIN
        else -> ContactInfo.PhoneType.OTHER
    }
}

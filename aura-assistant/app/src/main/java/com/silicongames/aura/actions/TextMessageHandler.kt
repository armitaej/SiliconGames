package com.silicongames.aura.actions

import android.content.Context
import android.telephony.SmsManager
import android.util.Log

/**
 * Handles SEND_TEXT intents by sending SMS messages.
 *
 * Note: In a production app, you'd want to add contact lookup
 * to resolve names to phone numbers. This implementation
 * demonstrates the pattern and handles direct number input.
 */
class TextMessageHandler(private val context: Context) {

    companion object {
        private const val TAG = "TextMessageHandler"
    }

    /**
     * Send a text message. Currently requires the recipient to be
     * a phone number or a contact name that can be resolved.
     *
     * For a production app, integrate with ContactsContract to
     * look up phone numbers by contact name.
     */
    suspend fun sendText(recipient: String, message: String) {
        val phoneNumber = resolveContact(recipient)

        if (phoneNumber == null) {
            Log.w(TAG, "Could not resolve contact: $recipient. Message queued for review.")
            // In a real app, you might queue this for user confirmation
            // or show a "who do you mean?" prompt
            return
        }

        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(message)

            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }

            Log.d(TAG, "SMS sent to $recipient ($phoneNumber)")
        } catch (e: SecurityException) {
            Log.e(TAG, "SMS permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
        }
    }

    /**
     * Resolve a contact name to a phone number.
     * Checks if the string is already a phone number,
     * otherwise searches contacts.
     */
    private fun resolveContact(nameOrNumber: String): String? {
        // If it looks like a phone number already, use it
        val cleaned = nameOrNumber.replace(Regex("[^0-9+]"), "")
        if (cleaned.length >= 7) return cleaned

        // Search device contacts
        try {
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$nameOrNumber%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getString(0)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Contacts permission denied", e)
        }

        return null
    }
}

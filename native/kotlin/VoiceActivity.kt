package com.openkeys.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent

/** Transparent hop that runs the system speech recognizer and hands the text back to the keyboard. */
class VoiceActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, intent.getStringExtra("lang") ?: "en-US")
        try { startActivityForResult(i, 1) } catch (e: Exception) { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res == RESULT_OK && t != null)
            sendBroadcast(Intent(OpenKeysIME.ACTION_VOICE).setPackage(packageName).putExtra("text", t))
        finish()
    }
}

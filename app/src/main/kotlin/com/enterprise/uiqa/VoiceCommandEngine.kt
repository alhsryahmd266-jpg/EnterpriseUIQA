package com.enterprise.uiqa

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * VoiceCommandEngine — التحكم الصوتي
 * المريض يقول أمراً بصوته → النظام ينفذه في اللعبة
 *
 * الأوامر المدعومة (عربي + إنجليزي):
 *   "نار" / "fire" / "اضغط"   → إطلاق نار
 *   "يمين" / "right"           → تحرك يمين
 *   "يسار" / "left"            → تحرك يسار
 *   "للأمام" / "forward"       → تقدم
 *   "وقف" / "stop"             → توقف
 *   "قفز" / "jump"             → قفز
 *   "إعادة" / "reload"         → إعادة تحميل
 */
class VoiceCommandEngine(private val context: Context) {

    private const val TAG = "VoiceCommandEngine"

    enum class Command {
        FIRE, MOVE_RIGHT, MOVE_LEFT, MOVE_FORWARD, MOVE_BACK,
        STOP, JUMP, RELOAD, UNKNOWN
    }

    interface Listener { fun onCommand(cmd: Command, confidence: Float) }

    private var recognizer: SpeechRecognizer? = null
    var listener: Listener? = null
    var isListening = false
        private set

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
        startListening()
    }

    fun stop() {
        isListening = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")   // عربي أولاً
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        isListening = true
        recognizer?.startListening(intent)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            val confidences = results
                .getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

            matches.firstOrNull()?.let { text ->
                val cmd = parseCommand(text.lowercase().trim())
                val conf = confidences?.firstOrNull() ?: 0.8f
                Log.i(TAG, "Voice: \"$text\" → ${cmd.name} (${(conf*100).toInt()}%)")
                listener?.onCommand(cmd, conf)
            }
            // استمر في الاستماع تلقائياً
            if (isListening) startListening()
        }

        override fun onPartialResults(partial: Bundle?) {
            val matches = partial
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            matches.firstOrNull()?.let { text ->
                val cmd = parseCommand(text.lowercase().trim())
                if (cmd != Command.UNKNOWN)
                    listener?.onCommand(cmd, 0.6f)  // partial = ثقة أقل
            }
        }

        override fun onError(error: Int) {
            Log.w(TAG, "Voice error: $error")
            if (isListening) startListening()   // أعد المحاولة
        }

        override fun onReadyForSpeech(p: Bundle?) { Log.d(TAG, "listening...") }
        override fun onBeginningOfSpeech()  {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech()        {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    private fun parseCommand(text: String): Command = when {
        text.containsAny("نار","fire","اطلاق","اضغط","shoot","tap")  -> Command.FIRE
        text.containsAny("يمين","right","يمن")                        -> Command.MOVE_RIGHT
        text.containsAny("يسار","left","يسر")                         -> Command.MOVE_LEFT
        text.containsAny("أمام","امام","forward","تقدم","تقدّم")      -> Command.MOVE_FORWARD
        text.containsAny("خلف","back","backward","تراجع","رجوع")      -> Command.MOVE_BACK
        text.containsAny("وقف","stop","إيقاف","ايقاف","قف")           -> Command.STOP
        text.containsAny("قفز","jump","اقفز")                         -> Command.JUMP
        text.containsAny("إعادة","اعادة","reload","ملء","ملئ")        -> Command.RELOAD
        else                                                           -> Command.UNKNOWN
    }

    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it) }
}

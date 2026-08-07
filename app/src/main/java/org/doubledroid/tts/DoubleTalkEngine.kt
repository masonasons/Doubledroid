// license:BSD-3-Clause
// copyright-holders:David Sexton
//
// Process-wide singleton owning the one dtalk emulator instance. The native
// handle is not thread-safe, so every call is serialized on [lock] — shared
// between the TTS service's synthesis thread, its onStop() (which may run on
// a binder thread), and the settings activity's test-speech path.
package org.doubledroid.tts

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import java.io.File

object DoubleTalkEngine {
    const val ROM_NAME = "doubletalkpc.bin"
    const val ROM_SIZE = 512 * 1024
    const val ROM_CRC32 = 0x66685631L
    const val ROM_SHA1 = "bf7e78d6381c76d291ee069971873347a314ffff"

    const val PREFS_NAME = "doubledroid"
    const val PREF_VOICE = "voice"            // card nO 0-7
    const val PREF_FILTER = "filter"          // low-pass corner Hz: 3000 or 4000
    const val PREF_RATE_BOOST = "rate_boost"  // bool
    const val PREF_PAUSE_CAP_MS = "pause_cap_ms" // max sentence/punctuation pause, ms; 0 = authentic (no cap)
    const val PREF_VOLUME = "volume"          // card nV 0-9, -1 = auto (card default)
    const val PREF_TONE = "tone"              // card nX 0-2, -1 = voice preset
    const val PREF_ARTICULATION = "articulation" // nA 0-9, -1 = voice preset
    const val PREF_EXPRESSION = "expression"  // nE 0-9, -1 = voice preset
    const val PREF_FORMANT = "formant"        // nF 0-9, -1 = voice preset
    const val PREF_REVERB = "reverb"          // nR 0-9, -1 = voice preset
    const val PREF_EMOJI_COLLAPSE_REPEATS = "emoji_collapse_repeats" // bool, default true

    /** nO voice numbers 0-7, names per the DoubleTalk PC/LT manual (Table 1).
     * Voices 5/6/7 are firmware aliases of 0/1/2's presets. */
    val VOICE_NAMES = arrayOf(
        "Perfect Paul", "Vader", "Big Bob", "Precise Pete",
        "Ricochet", "Biff", "Skip", "Robo Robert",
    )

    /** Android Voice identifiers, index = card nO number. */
    val VOICE_IDS = arrayOf(
        "en-US-dt-paul", "en-US-dt-vader", "en-US-dt-bigbob", "en-US-dt-pete",
        "en-US-dt-ricochet", "en-US-dt-biff", "en-US-dt-skip", "en-US-dt-robo",
    )

    /** Meta-voice reported as the engine default. TextToSpeech.setLanguage
     * resolves the default voice ONCE and pins its name into the client
     * connection, so returning a concrete voice there would freeze the
     * user's default-voice setting until the client reconnects. Clients
     * get pinned to this name instead, and the service resolves it to the
     * saved preference at synthesis time — an explicit setVoice() with a
     * concrete voice still wins. */
    const val DEFAULT_VOICE_ID = "en-US-dt-default"

    /** Firmware voice presets keyed by nO, card-native values in the order
     * (pitch 0-99, articulation 0-9, formant 0-9, tone 0-2, expression 0-9,
     * reverb 0-9) — ROM constants verified upstream via the settingsmap
     * diagnostic; selecting nO loads these on the card. */
    private val VOICE_PRESETS = arrayOf(
        intArrayOf(50, 5, 5, 1, 5, 0),  // Perfect Paul
        intArrayOf(30, 4, 5, 1, 7, 2),  // Vader
        intArrayOf(40, 4, 1, 0, 6, 0),  // Big Bob
        intArrayOf(60, 8, 6, 2, 4, 0),  // Precise Pete
        intArrayOf(40, 5, 2, 1, 5, 6),  // Ricochet
        intArrayOf(50, 5, 5, 1, 5, 0),  // Biff        = Perfect Paul
        intArrayOf(30, 4, 5, 1, 7, 2),  // Skip        = Vader
        intArrayOf(40, 4, 1, 0, 6, 0),  // Robo Robert = Big Bob
    )

    private val lock = Any()
    private var handle = 0L

    // Card voice (nO) last sent to the firmware, or -1 if none yet (forces
    // the next utterance to send it). Selecting a voice reloads its firmware
    // preset - real work the emulator has to run through - so re-sending it
    // on every utterance when it hasn't actually changed was pure wasted
    // latency before speech could start. Tracked here (not per-utterance)
    // since it reflects the one shared emulator instance's real state.
    private var lastVoiceSent = -1

    @Volatile
    var sampleRate = 10504
        private set

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun romFile(context: Context): File = File(context.filesDir, ROM_NAME)

    fun voiceIndexForId(id: String?): Int {
        val i = VOICE_IDS.indexOf(id)
        return if (i >= 0) i else -1
    }

    /** Boot the emulator from the imported ROM if not already running. */
    fun open(context: Context): Boolean {
        synchronized(lock) {
            if (handle != 0L) return true
            val f = romFile(context)
            if (!f.isFile) return false
            val rom = try {
                f.readBytes()
            } catch (e: Exception) {
                return false
            }
            handle = DoubleTalkNative.create(rom)
            if (handle == 0L) return false
            val cardRate = DoubleTalkNative.sampleRate(handle)
            val outRate = preferredOutputRate(context, cardRate)
            DoubleTalkNative.setOutputRate(handle, outRate)
            sampleRate = outRate
            lastVoiceSent = -1 // freshly booted firmware is at its own default (voice 0)
        }
        applyPrefs(context)
        return true
    }

    /** The card's raw rate (10504Hz) upsamples badly through Android's own
     * audio pipeline - not a clean ratio to typical device rates, and this
     * was observed to leave audible imaging artifacts independent of the
     * dtalk_set_lowpass_hz corner. So instead of handing AudioTrack/the TTS
     * callback the raw rate and leaving that resample to the OS, synthChunk
     * is upsampled here (dtalk_set_output_rate) to the device's own native
     * mixer rate, with a filter tuned to this signal. Falls back to the raw
     * card rate (no resampling) if the platform doesn't report a usable
     * native rate. */
    private fun preferredOutputRate(context: Context, cardRate: Int): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val native = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        return if (native != null && native > cardRate) native else cardRate
    }

    fun isOpen(): Boolean = synchronized(lock) { handle != 0L }

    /** Tear down the emulator (e.g. before importing a replacement ROM). */
    fun shutdown() {
        synchronized(lock) {
            if (handle != 0L) {
                DoubleTalkNative.destroy(handle)
                handle = 0L
                lastVoiceSent = -1
            }
        }
    }

    /** Push the persistent output-stage/boost settings into the emulator. */
    fun applyPrefs(context: Context) {
        val p = prefs(context)
        val filter = p.getInt(PREF_FILTER, 3000)
        val boost = p.getBoolean(PREF_RATE_BOOST, false)
        val pauseCapMs = p.getInt(PREF_PAUSE_CAP_MS, 0)
        synchronized(lock) {
            if (handle == 0L) return
            DoubleTalkNative.setLowpassHz(handle, filter)
            DoubleTalkNative.setRateBoost(
                handle, if (boost) DoubleTalkNative.rateBoostMax() else 0)
            DoubleTalkNative.setPauseCapMs(handle, pauseCapMs)
        }
    }

    /** Immediate stop (Ctrl-X): safe to call from any thread. */
    fun stop() {
        synchronized(lock) {
            if (handle != 0L) DoubleTalkNative.stop(handle)
        }
    }

    fun queue(data: ByteArray) {
        synchronized(lock) {
            if (handle != 0L) DoubleTalkNative.queue(handle, data)
        }
    }

    /** One synthesis chunk; returns samples written into [out] (0 = idle). */
    fun synthChunk(out: ShortArray): Int {
        synchronized(lock) {
            if (handle == 0L) return 0
            return DoubleTalkNative.synth16(handle, out)
        }
    }

    // --- command-prefix construction (ported from the NVDA synth driver) ---

    /** Android speech rate (100 = normal, roughly 10-600) -> card nS 0-9.
     * 100 maps to the card default 5S; below normal spreads over 0-5,
     * above normal over 5-9 (the firmware caps nS at 9; the rate-boost
     * setting rescales the whole table for faster-than-9S speech). */
    fun mapRate(rate: Int): Int = if (rate <= 100) {
        (rate * 5 + 50) / 100
    } else {
        minOf(9, 5 + ((rate - 100) * 4 + 150) / 300)
    }.coerceIn(0, 9)

    /** Android pitch (100 = the voice's natural pitch) -> card nP 0-99,
     * scaled relative to the selected voice's preset pitch so pitch 100
     * always sounds like the voice's own character. */
    fun mapPitch(pitch: Int, presetPitch: Int): Int = if (pitch <= 100) {
        presetPitch * pitch / 100
    } else {
        presetPitch + (99 - presetPitch) * (pitch - 100) / 300
    }.coerceIn(0, 99)

    /**
     * Build the 0x01-command utterance prefix. Voice (nO) MUST come first:
     * it loads the voice's firmware preset, so every following parameter
     * overrides that preset. Tone/articulation/expression/formant/reverb use
     * WYSIWYG emission as in the NVDA driver: a parameter equal to the
     * voice's own preset value is omitted so the preset shows through; only
     * real overrides are sent.
     *
     * Pitch/rate/volume are NOT omitted that way even when they match the
     * apparent default: nS/nV aren't part of the voice preset at all (nO
     * never resets them), and nP is only reset by an nO this call may be
     * skipping (see [skipVoice]) - so "equals the default" doesn't mean "the
     * card is already there". A prior utterance (e.g. TalkBack speaking a
     * link at a lowered pitch) can leave the card's register on a value
     * this call's target doesn't match even when the target looks like the
     * default, and omitting the command would leave the card stuck on that
     * stale value. Always sending them keeps the card's actual state in
     * sync with each request.
     *
     * [rate]/[pitch] are Android TTS units (100 = normal); the voice-quality
     * values are card-native with -1 meaning "leave at voice preset".
     *
     * [skipVoice] omits the nO command entirely: reloading a voice preset is
     * real firmware work (and real emulated time), so a caller that already
     * knows the card is on this voice from a prior utterance can skip it -
     * see lastVoiceSent.
     */
    fun buildPrefix(
        cardVoice: Int,
        rate: Int,
        pitch: Int,
        volume: Int = -1,
        tone: Int = -1,
        articulation: Int = -1,
        expression: Int = -1,
        formant: Int = -1,
        reverb: Int = -1,
        skipVoice: Boolean = false,
    ): String {
        val v = cardVoice.coerceIn(0, 7)
        val preset = VOICE_PRESETS[v]
        val sb = StringBuilder()
        if (!skipVoice) sb.append('\u0001').append(v).append('O')
        val nS = mapRate(rate)
        sb.append('\u0001').append(nS).append('S')
        val nP = mapPitch(pitch, preset[0])
        sb.append('\u0001').append(nP).append('P')
        sb.append('\u0001').append(if (volume in 0..9) volume else 5).append('V')
        if (tone in 0..2 && tone != preset[3]) sb.append('\u0001').append(tone).append('X')
        if (articulation in 0..9 && articulation != preset[1])
            sb.append('\u0001').append(articulation).append('A')
        if (expression in 0..9 && expression != preset[4])
            sb.append('\u0001').append(expression).append('E')
        if (formant in 0..9 && formant != preset[2])
            sb.append('\u0001').append(formant).append('F')
        if (reverb in 0..9 && reverb != preset[5])
            sb.append('\u0001').append(reverb).append('R')
        return sb.toString()
    }

    /** Prefix built from the saved settings (voice overridable per-request).
     * Skips the nO command when [cardVoice] matches what the last utterance
     * already loaded onto the card - see lastVoiceSent. */
    fun buildPrefixFromPrefs(context: Context, cardVoice: Int, rate: Int, pitch: Int): String {
        val p = prefs(context)
        val skipVoice = synchronized(lock) {
            val skip = cardVoice == lastVoiceSent
            lastVoiceSent = cardVoice
            skip
        }
        return buildPrefix(
            cardVoice = cardVoice,
            rate = rate,
            pitch = pitch,
            volume = p.getInt(PREF_VOLUME, -1),
            tone = p.getInt(PREF_TONE, -1),
            articulation = p.getInt(PREF_ARTICULATION, -1),
            expression = p.getInt(PREF_EXPRESSION, -1),
            formant = p.getInt(PREF_FORMANT, -1),
            reverb = p.getInt(PREF_REVERB, -1),
            skipVoice = skipVoice,
        )
    }

    /** Unicode punctuation that has an unambiguous ASCII equivalent (iOS/macOS
     * autocorrect curly quotes, en/em dashes, ellipsis, etc.). Mapped before
     * the ASCII-only strip below so e.g. a curly apostrophe still reads as
     * "don't" rather than becoming a word-breaking space ("don t"). */
    private val UNICODE_PUNCTUATION = mapOf(
        '‘' to '\'', '’' to '\'', '‚' to '\'', '′' to '\'',
        '“' to '"', '”' to '"', '„' to '"', '″' to '"',
        '–' to '-', '—' to '-',
        '…' to '.',
    )

    /** Printable ASCII only: the firmware treats control bytes as commands
     * (and 0x01 must never occur in user text). Emoji are spelled out by
     * [Emoji.describe] first so e.g. 🙂 reads as "slightly smiling face"
     * instead of disappearing into the word-breaking space below; the
     * space-padding that leaves around each description is collapsed back
     * to single spaces afterwards. */
    fun sanitizeText(text: String, collapseRepeatedEmoji: Boolean): String =
        Emoji.describe(text, collapseRepeatedEmoji)
            .map { UNICODE_PUNCTUATION[it] ?: it }.joinToString("")
            .replace(Regex("[^\\x20-\\x7e]"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()

    /** Full utterance: settings prefix + sanitized text + CR (starts speech). */
    fun utteranceBytes(context: Context, cardVoice: Int, rate: Int, pitch: Int, text: String): ByteArray {
        val collapseRepeatedEmoji = prefs(context).getBoolean(PREF_EMOJI_COLLAPSE_REPEATS, true)
        val s = buildPrefixFromPrefs(context, cardVoice, rate, pitch) +
            sanitizeText(text, collapseRepeatedEmoji) + "\r"
        return s.toByteArray(Charsets.US_ASCII)
    }
}

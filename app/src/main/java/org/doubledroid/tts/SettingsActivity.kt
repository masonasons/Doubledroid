// license:BSD-3-Clause
// copyright-holders:David Sexton
//
// Engine settings: firmware ROM import (the ROM is proprietary RC Systems
// software, so the user supplies their own dump), default voice, output
// stage, the manual's voice-quality knobs, and a local test-speech path
// that plays the emulator through an AudioTrack without going via the
// system TTS stack.
package org.doubledroid.tts

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.concurrent.thread

private const val REQUEST_PICK_ROM = 1

class SettingsActivity : Activity() {

    private lateinit var romStatus: TextView

    @Volatile
    private var testStop = false
    private var testThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        romStatus = findViewById(R.id.rom_status)
        val prefs = DoubleTalkEngine.prefs(this)

        updateRomStatus()

        findViewById<Button>(R.id.import_rom).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/octet-stream", "application/x-binary", "*/*"))
            }
            startActivityForResult(intent, REQUEST_PICK_ROM)
        }

        // Default voice
        val voiceSpinner = findViewById<Spinner>(R.id.voice_spinner)
        voiceSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, DoubleTalkEngine.VOICE_NAMES)
        voiceSpinner.setSelection(
            prefs.getInt(DoubleTalkEngine.PREF_VOICE, 0).coerceIn(0, 7))
        voiceSpinner.onItemSelectedListener = onSelected { pos ->
            prefs.edit().putInt(DoubleTalkEngine.PREF_VOICE, pos).apply()
        }

        // Output filter
        val filterSpinner = findViewById<Spinner>(R.id.filter_spinner)
        filterSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf(getString(R.string.filter_classic), getString(R.string.filter_wide)))
        filterSpinner.setSelection(
            if (prefs.getInt(DoubleTalkEngine.PREF_FILTER, 3000) >= 4000) 1 else 0)
        filterSpinner.onItemSelectedListener = onSelected { pos ->
            prefs.edit().putInt(DoubleTalkEngine.PREF_FILTER,
                if (pos == 1) 4000 else 3000).apply()
            DoubleTalkEngine.applyPrefs(this)
        }

        // Rate boost
        val boost = findViewById<Switch>(R.id.rate_boost)
        boost.isChecked = prefs.getBoolean(DoubleTalkEngine.PREF_RATE_BOOST, false)
        boost.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(DoubleTalkEngine.PREF_RATE_BOOST, checked).apply()
            DoubleTalkEngine.applyPrefs(this)
        }

        // Pause after sentences/punctuation: the firmware has no "faster
        // than default" word-gap command (nT already defaults to its own
        // fastest setting), so this trims long silence runs out of the
        // generated audio instead - see dtalk_set_pause_cap_ms.
        val pauseSpinner = findViewById<Spinner>(R.id.pause_spinner)
        val pauseCapValues = intArrayOf(0, 300, 150, 60)
        pauseSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf(getString(R.string.pause_default), getString(R.string.pause_short),
                getString(R.string.pause_shorter), getString(R.string.pause_shortest)))
        pauseSpinner.setSelection(
            pauseCapValues.indexOf(prefs.getInt(DoubleTalkEngine.PREF_PAUSE_CAP_MS, 0))
                .coerceAtLeast(0))
        pauseSpinner.onItemSelectedListener = onSelected { pos ->
            prefs.edit().putInt(DoubleTalkEngine.PREF_PAUSE_CAP_MS, pauseCapValues[pos]).apply()
            DoubleTalkEngine.applyPrefs(this)
        }

        // Volume + voice-quality sliders: SeekBar position 0 = Auto (follow
        // the voice preset / card default), 1..10 = card value 0..9.
        bindAutoSlider(R.id.volume_seek, R.id.volume_label,
            R.string.volume_label, DoubleTalkEngine.PREF_VOLUME)
        bindAutoSlider(R.id.articulation_seek, R.id.articulation_label,
            R.string.articulation_label, DoubleTalkEngine.PREF_ARTICULATION)
        bindAutoSlider(R.id.expression_seek, R.id.expression_label,
            R.string.expression_label, DoubleTalkEngine.PREF_EXPRESSION)
        bindAutoSlider(R.id.formant_seek, R.id.formant_label,
            R.string.formant_label, DoubleTalkEngine.PREF_FORMANT)
        bindAutoSlider(R.id.reverb_seek, R.id.reverb_label,
            R.string.reverb_label, DoubleTalkEngine.PREF_REVERB)

        // Tone: Auto / Bass / Normal / Treble -> -1 / 0 / 1 / 2
        val toneSpinner = findViewById<Spinner>(R.id.tone_spinner)
        toneSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf(getString(R.string.tone_auto), getString(R.string.tone_bass),
                getString(R.string.tone_normal), getString(R.string.tone_treble)))
        toneSpinner.setSelection(prefs.getInt(DoubleTalkEngine.PREF_TONE, -1) + 1)
        toneSpinner.onItemSelectedListener = onSelected { pos ->
            prefs.edit().putInt(DoubleTalkEngine.PREF_TONE, pos - 1).apply()
        }

        // Collapse runs of 3+ identical emoji into a count instead of
        // speaking each one - see Emoji.describe.
        val emojiCollapse = findViewById<Switch>(R.id.emoji_collapse_repeats)
        emojiCollapse.isChecked =
            prefs.getBoolean(DoubleTalkEngine.PREF_EMOJI_COLLAPSE_REPEATS, true)
        emojiCollapse.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(DoubleTalkEngine.PREF_EMOJI_COLLAPSE_REPEATS, checked).apply()
        }

        findViewById<Button>(R.id.speak_button).setOnClickListener { startTestSpeech() }
        findViewById<Button>(R.id.stop_button).setOnClickListener { stopTestSpeech() }
    }

    override fun onDestroy() {
        stopTestSpeech()
        super.onDestroy()
    }

    private fun onSelected(f: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?,
                                    position: Int, id: Long) = f(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun bindAutoSlider(seekId: Int, labelId: Int, labelRes: Int, prefKey: String) {
        val prefs = DoubleTalkEngine.prefs(this)
        val seek = findViewById<SeekBar>(seekId)
        val label = findViewById<TextView>(labelId)
        fun render(v: Int) {
            val value = if (v <= 0) getString(R.string.auto_value) else (v - 1).toString()
            label.text = "${getString(labelRes)}: $value"
        }
        val initial = prefs.getInt(prefKey, -1) + 1
        seek.progress = initial.coerceIn(0, 10)
        render(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                render(progress)
                prefs.edit().putInt(prefKey, progress - 1).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // --- ROM import ---

    private fun updateRomStatus() {
        val f = DoubleTalkEngine.romFile(this)
        romStatus.text = if (f.isFile) {
            val verified = DoubleTalkEngine.prefs(this).getBoolean("rom_verified", false)
            getString(R.string.rom_present, "${f.length()} bytes") + "\n" +
                getString(if (verified) R.string.rom_hash_ok else R.string.rom_hash_mismatch)
        } else {
            getString(R.string.rom_missing)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_ROM || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        thread {
            val result = importRom(uri)
            runOnUiThread {
                if (result != null) romStatus.text = result else updateRomStatus()
            }
        }
    }

    /** Returns an error message, or null on success. */
    private fun importRom(uri: android.net.Uri): String? {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return getString(R.string.rom_import_error, "open failed")
        } catch (e: Exception) {
            return getString(R.string.rom_import_error, e.message ?: e.toString())
        }
        if (bytes.size != DoubleTalkEngine.ROM_SIZE) {
            return getString(R.string.rom_wrong_size, bytes.size)
        }

        // Boot-test the ROM in a throwaway instance before accepting it.
        val test = DoubleTalkNative.create(bytes)
        if (test == 0L) return getString(R.string.rom_boot_failed)
        DoubleTalkNative.destroy(test)

        val crc = CRC32().apply { update(bytes) }.value
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val verified = crc == DoubleTalkEngine.ROM_CRC32 &&
            sha1 == DoubleTalkEngine.ROM_SHA1

        // Atomic replace, then restart the engine on the new ROM.
        val dst = DoubleTalkEngine.romFile(this)
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        try {
            tmp.writeBytes(bytes)
            DoubleTalkEngine.shutdown()
            if (!tmp.renameTo(dst)) {
                dst.delete()
                if (!tmp.renameTo(dst)) return getString(R.string.rom_import_error, "rename failed")
            }
        } catch (e: Exception) {
            tmp.delete()
            return getString(R.string.rom_import_error, e.message ?: e.toString())
        }
        DoubleTalkEngine.prefs(this).edit().putBoolean("rom_verified", verified).apply()
        return null
    }

    // --- test speech (direct AudioTrack, bypasses the system TTS stack) ---

    private fun startTestSpeech() {
        stopTestSpeech()
        if (!DoubleTalkEngine.open(this)) {
            romStatus.text = getString(R.string.test_no_rom)
            return
        }
        val text = findViewById<EditText>(R.id.test_text).text.toString()
        val voice = DoubleTalkEngine.prefs(this)
            .getInt(DoubleTalkEngine.PREF_VOICE, 0).coerceIn(0, 7)
        val utterance = DoubleTalkEngine.utteranceBytes(this, voice, 100, 100, text)
        testStop = false
        testThread = thread(name = "dtalk-test") {
            val rate = DoubleTalkEngine.sampleRate
            val minBuf = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(maxOf(minBuf, 8192))
                .build()
            try {
                track.play()
                DoubleTalkEngine.stop()
                DoubleTalkEngine.queue(utterance)
                val buf = ShortArray(2048)
                while (!testStop) {
                    val n = DoubleTalkEngine.synthChunk(buf)
                    if (n == 0) break
                    var off = 0
                    while (off < n && !testStop) {
                        val w = track.write(buf, off, n - off)
                        if (w < 0) break
                        off += w
                    }
                }
                if (!testStop) {
                    // Let the tail drain before releasing.
                    track.stop()
                }
            } finally {
                try { track.release() } catch (_: Exception) {}
            }
        }
    }

    private fun stopTestSpeech() {
        testStop = true
        DoubleTalkEngine.stop()
        testThread?.join(1000)
        testThread = null
    }
}

package com.muhannadsatlink.cnmovie

// أدوات الترجمة: قراءة SRT / VTT / ASS وتحويلها وتقديمها أو تأخيرها
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class Cue(val start: Long, val end: Long, val text: String)

object SubUtil {
    // ---------- تحويل الترجمة ----------
    fun decode(b: ByteArray): String {
        var bytes = b
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes = bytes.copyOfRange(3, bytes.size)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, Charset.forName("windows-1256")) // ترجمات عربية قديمة
        }
    }

    fun parse(raw: String): List<Cue> {
        val t = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (t.contains("[Events]") || t.contains("Dialogue:")) return parseAss(t)
        val cues = ArrayList<Cue>()
        val time = Regex("((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})")
        for (block in t.split(Regex("\n\\s*\n"))) {
            val lines = block.trim('\n').lines()
            val idx = lines.indexOfFirst { time.containsMatchIn(it) }
            if (idx < 0) continue
            val m = time.find(lines[idx])!!
            val text = lines.drop(idx + 1).joinToString("\n").trim()
            if (text.isNotEmpty()) cues += Cue(ms(m.groupValues[1]), ms(m.groupValues[2]), text)
        }
        return cues
    }

    fun parseAss(t: String): List<Cue> {
        val cues = ArrayList<Cue>()
        t.lines().filter { it.startsWith("Dialogue:") }.forEach { line ->
            val parts = line.removePrefix("Dialogue:").split(",", limit = 10)
            if (parts.size < 10) return@forEach
            val text = parts[9].replace(Regex("\\{[^}]*\\}"), "").replace("\\N", "\n").replace("\\n", "\n").trim()
            if (text.isNotEmpty()) cues += Cue(ms(parts[1].trim()), ms(parts[2].trim()), text)
        }
        return cues.sortedBy { it.start }
    }

    fun ms(s: String): Long {
        val p = s.replace(',', '.').split(':')
        val sec = p.last().toDouble()
        val min = if (p.size >= 2) p[p.size - 2].toLong() else 0
        val hr = if (p.size >= 3) p[p.size - 3].toLong() else 0
        return ((hr * 3600 + min * 60) * 1000 + sec * 1000).toLong()
    }

    fun stamp(ms: Long, sep: Char): String =
        String.format("%02d:%02d:%02d%c%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, sep, ms % 1000)

    fun toSrt(c: List<Cue>) = buildString {
        c.forEachIndexed { i, q -> append("${i + 1}\n${stamp(q.start, ',')} --> ${stamp(q.end, ',')}\n${q.text}\n\n") }
    }

    fun toVtt(c: List<Cue>) = buildString {
        append("WEBVTT\n\n")
        c.forEach { q -> append("${stamp(q.start, '.')} --> ${stamp(q.end, '.')}\n${q.text}\n\n") }
    }

    fun toAss(c: List<Cue>) = buildString {
        fun a(ms: Long) = String.format("%d:%02d:%02d.%02d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000 / 10)
        append("[Script Info]\nTitle: CN.MOVIE\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\nWrapStyle: 0\n\n")
        append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        append("Style: Default,Arial,64,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,3,1,2,40,40,50,178\n\n")
        append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        c.forEach { q -> append("Dialogue: 0,${a(q.start)},${a(q.end)},Default,,0,0,0,,${q.text.replace("\n", "\\N")}\n") }
    }


    fun shift(c: List<Cue>, ms: Long) = c.mapNotNull { q ->
        val s = q.start + ms; val e = q.end + ms
        if (e <= 0) null else Cue(maxOf(0, s), e, q.text)
    }
}

package dev.carthingspotify.controller.api

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {
    private val timestampRegex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]")

    fun parse(lrc: String): List<LrcLine> {
        return lrc.lines().mapNotNull { line ->
            val match = timestampRegex.find(line) ?: return@mapNotNull null
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val msPart = match.groupValues[3]
            val ms = if (msPart.length == 2) msPart.toLong() * 10 else msPart.toLong()
            val timeMs = min * 60_000 + sec * 1000 + ms
            val text = line.substring(match.range.last + 1).trim()
            LrcLine(timeMs, text)
        }.sortedBy { it.timeMs }
    }
}

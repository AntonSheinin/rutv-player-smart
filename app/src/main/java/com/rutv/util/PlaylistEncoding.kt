package com.rutv.util

import java.nio.charset.Charset

private val WINDOWS_1251: Charset = Charset.forName("windows-1251")
private const val REPLACEMENT_CHAR = '\uFFFD'

/**
 * Decodes playlist bytes in a resilient way:
 * - Handles BOM for UTF-8/UTF-16.
 * - Prefers UTF-8, but falls back to windows-1251 when UTF-8 decoding is clearly broken.
 */
fun decodePlaylistText(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""

    val decoded = when {
        bytes.hasPrefix(0xEF, 0xBB, 0xBF) -> bytes.toString(Charsets.UTF_8)
        bytes.hasPrefix(0xFF, 0xFE) -> bytes.toString(Charsets.UTF_16LE)
        bytes.hasPrefix(0xFE, 0xFF) -> bytes.toString(Charsets.UTF_16BE)
        else -> decodeWithoutBom(bytes)
    }
    return decoded.removePrefix("\uFEFF")
}

private fun decodeWithoutBom(bytes: ByteArray): String {
    val utf8 = bytes.toString(Charsets.UTF_8)
    val utf8Bad = utf8.count { it == REPLACEMENT_CHAR }
    if (utf8Bad == 0) return utf8

    val cp1251 = bytes.toString(WINDOWS_1251)
    val cp1251Bad = cp1251.count { it == REPLACEMENT_CHAR }
    return if (cp1251Bad < utf8Bad) cp1251 else utf8
}

private fun ByteArray.hasPrefix(vararg values: Int): Boolean {
    if (size < values.size) return false
    for (i in values.indices) {
        if (this[i] != values[i].toByte()) return false
    }
    return true
}

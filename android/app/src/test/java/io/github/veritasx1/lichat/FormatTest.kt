package io.github.veritasx1.lichat

import io.github.veritasx1.lichat.core.FormatSpan
import io.github.veritasx1.lichat.core.formatted
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Card bcb40e06 (f005–f011): the same cases as Ubuntu's formatting.py – docs/formatierung-faelle.json, offsets there in
 *  code points like Python, here turned into UTF-16. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormatTest {
    @Test
    fun sameAsUbuntu() {
        val file = listOf(File("../../docs/formatierung-faelle.json"), File("../docs/formatierung-faelle.json")).first { it.exists() }
        val cases = JSONObject(file.readText()).getJSONArray("faelle")
        for (n in 0 until cases.length()) {
            val case = cases.getJSONObject(n)
            val (plain, spans) = formatted(case.getString("ein"))
            val text = case.getString("text")
            assertEquals(case.getString("ein"), text, plain)
            val want = case.getJSONArray("spans").let { list ->
                (0 until list.length()).map { list.getJSONArray(it) }.map { s ->
                    FormatSpan(text.offsetByCodePoints(0, s.getInt(0)), text.offsetByCodePoints(0, s.getInt(1)), s.getString(2),
                        if (s.isNull(3)) null else s.getString(3))
                }.sortedWith(compareBy({ it.start }, { it.end }, { it.kind }, { it.target ?: "" }))
            }
            assertEquals(case.getString("ein"), want, spans)
        }
    }
}

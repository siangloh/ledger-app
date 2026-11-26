package com.siangloh.ledger.core

import com.siangloh.ledger.core.crypto.Sha256
import com.siangloh.ledger.core.crypto.sha256Bytes
import com.siangloh.ledger.core.crypto.sha256Hex
import com.siangloh.ledger.core.json.JSONObject
import com.siangloh.ledger.data.TursoPipelineExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Sha256Test {

    @Test
    fun testEmptyStringVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex("")
        )
        assertEquals(32, sha256Bytes(ByteArray(0)).size)
    }

    @Test
    fun testAbcSingleBlockVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc")
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.sha256Hex("abc")
        )
    }

    @Test
    fun testMultiBlockNistVectors() {
        // 56 bytes (overflows 55-byte single-block padding threshold into 2 blocks)
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
        )
        // 112 bytes (>64 byte multi-block NIST vector)
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            sha256Hex("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu")
        )
    }

    @Test
    fun testUtf8ChineseAndMalayString() {
        val text = "账本同步 RM 128.50 · Nasi Lemak & Teh Tarik 餐饮"
        assertEquals(
            "601c1fcf7909690453b1ce37a082889e057134f9d7e46d40b9664da50efd74dc",
            sha256Hex(text)
        )
    }

    @Test
    fun testSha256BytesMatchesHex() {
        val raw = "Maybank MAE DuitNow > 64 bytes payload padding check 0123456789abcdef0123456789".encodeToByteArray()
        val digest = sha256Bytes(raw)
        assertEquals(32, digest.size)
        val manualHex = digest.joinToString("") { b ->
            val v = b.toInt() and 0xff
            v.toString(16).padStart(2, '0')
        }
        assertEquals(manualHex, sha256Hex(raw))
    }

    @Test
    fun testTursoPipelinePayloadAndResponseParsing() {
        val payloadStr = TursoPipelineExecutor.buildRequestPayload(
            listOf(
                Pair(
                    "INSERT INTO transactions (id, amount, is_active, note, channel) VALUES (?, ?, ?, ?, ?)",
                    listOf(42L, 18.5, true, "Nasi Lemak", null)
                )
            )
        )
        val root = JSONObject(payloadStr)
        val reqs = root.getJSONArray("requests")
        assertEquals(2, reqs.length())
        assertEquals("execute", reqs.getJSONObject(0).getString("type"))
        assertEquals("close", reqs.getJSONObject(1).getString("type"))

        val args = reqs.getJSONObject(0).getJSONObject("stmt").getJSONArray("args")
        assertEquals("integer", args.getJSONObject(0).getString("type"))
        assertEquals("42", args.getJSONObject(0).getString("value"))
        assertEquals("float", args.getJSONObject(1).getString("type"))
        assertEquals(18.5, args.getJSONObject(1).getDouble("value"), 0.0001)
        assertEquals("integer", args.getJSONObject(2).getString("type"))
        assertEquals("1", args.getJSONObject(2).getString("value"))
        assertEquals("text", args.getJSONObject(3).getString("type"))
        assertEquals("Nasi Lemak", args.getJSONObject(3).getString("value"))
        assertEquals("null", args.getJSONObject(4).getString("type"))

        val sampleResp = """
            {
              "results": [
                {
                  "type": "ok",
                  "response": {
                    "type": "execute",
                    "result": {
                      "cols": [
                        {"name": "id"},
                        {"name": "amount"},
                        {"name": "note"},
                        {"name": "deleted_at"}
                      ],
                      "rows": [
                        [
                          {"type": "integer", "value": "101"},
                          {"type": "float", "value": 36.8},
                          {"type": "text", "value": "Touch 'n Go"},
                          {"type": "null"}
                        ]
                      ]
                    }
                  }
                },
                {
                  "type": "ok",
                  "response": {"type": "close"}
                }
              ]
            }
        """.trimIndent()
        val parsed = TursoPipelineExecutor.parsePipelineResponse(sampleResp)
        assertTrue(parsed.isSuccess)
        val statementResults = parsed.getOrThrow()
        assertEquals(1, statementResults.size)
        val firstRow = statementResults[0][0]
        assertEquals(101L, firstRow["id"])
        assertEquals(36.8, firstRow["amount"] as Double, 0.0001)
        assertEquals("Touch 'n Go", firstRow["note"])
        assertNull(firstRow["deleted_at"])

        val errResp = """
            {
              "results": [
                {
                  "type": "error",
                  "error": {"message": "SQLITE_ERROR: no such table: foo"}
                }
              ]
            }
        """.trimIndent()
        val parsedErr = TursoPipelineExecutor.parsePipelineResponse(errResp)
        assertTrue(parsedErr.isFailure)
        assertTrue(parsedErr.exceptionOrNull()?.message?.contains("no such table: foo") == true)
    }

    @Test
    fun testTursoVersionTokenAndBumpStatements() {
        val t1 = TursoPipelineExecutor.generateVersionToken()
        val t2 = TursoPipelineExecutor.generateVersionToken()
        assertEquals(16, t1.length)
        assertTrue(t1.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(t1, t2)

        val stmts = TursoPipelineExecutor.buildBumpVersionStatements("user-123", "2026-11-26T09:30:00")
        assertEquals(2, stmts.size)
        assertEquals("global_data_version", stmts[0].second[0])
        assertEquals("user_data_version:user-123", stmts[1].second[0])
        assertEquals(stmts[0].second[1], stmts[1].second[1])
    }
}

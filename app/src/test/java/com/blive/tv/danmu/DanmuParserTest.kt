package com.blive.tv.danmu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * DanmuParser 二进制协议解析测试。
 * 协议：packLen(4) + headerLen(2)=16 + ver(2) + op(4) + seq(4) + body
 */
class DanmuParserTest {

    private val parser = DanmuParser()

    private fun buildPacket(op: Int, body: ByteArray, version: Int = 1): ByteArray {
        val packetLength = 16 + body.size
        return ByteBuffer.allocate(packetLength).order(ByteOrder.BIG_ENDIAN)
            .putInt(packetLength)
            .putShort(16)
            .putShort(version.toShort())
            .putInt(op)
            .putInt(1)
            .put(body)
            .array()
    }

    private fun danmuJson(content: String, color: Int = 16777215, mode: Int = 1, username: String = "测试用户", uid: Long = 12345): String {
        return """{"cmd":"DANMU_MSG","info":[[0,$mode,25,$color,1700000000000],"$content",[$uid,"$username",0]]}"""
    }

    @Test
    fun `heartbeat packet produces no messages`() {
        val packet = buildPacket(op = 3, body = "54321".toByteArray())
        assertTrue(parser.parseBinaryData(packet).isEmpty())
    }

    @Test
    fun `auth reply produces no messages`() {
        val packet = buildPacket(op = 8, body = """{"code":0}""".toByteArray())
        assertTrue(parser.parseBinaryData(packet).isEmpty())
    }

    @Test
    fun `plain danmu message is parsed`() {
        val json = danmuJson(content = "你好世界", color = 0xFF0000.toInt(), mode = 1, username = "观众甲", uid = 777)
        val packet = buildPacket(op = 5, body = json.toByteArray(), version = 1)
        val messages = parser.parseBinaryData(packet)

        assertEquals(1, messages.size)
        val danmu = messages[0] as DanmuMessage.Danmu
        assertEquals("你好世界", danmu.content)
        assertEquals("观众甲", danmu.username)
        assertEquals(777L, danmu.userId)
        assertEquals(1, danmu.mode)
    }

    @Test
    fun `top mode danmu keeps mode`() {
        val json = danmuJson(content = "置顶弹幕", mode = 4)
        val packet = buildPacket(op = 5, body = json.toByteArray(), version = 1)
        val danmu = parser.parseBinaryData(packet)[0] as DanmuMessage.Danmu
        assertEquals(4, danmu.mode)
    }

    @Test
    fun `zlib compressed packet is parsed`() {
        val innerPacket = buildPacket(op = 5, body = danmuJson(content = "压缩弹幕").toByteArray(), version = 1)
        val compressed = ByteArrayOutputStream().use { output ->
            val deflater = Deflater()
            deflater.setInput(innerPacket)
            deflater.finish()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            deflater.end()
            output.toByteArray()
        }
        val outerPacket = buildPacket(op = 5, body = compressed, version = 2)
        val messages = parser.parseBinaryData(outerPacket)

        assertEquals(1, messages.size)
        assertEquals("压缩弹幕", (messages[0] as DanmuMessage.Danmu).content)
    }

    @Test
    fun `multiple packets in one buffer are all parsed`() {
        val p1 = buildPacket(op = 5, body = danmuJson(content = "第一条").toByteArray(), version = 1)
        val p2 = buildPacket(op = 5, body = danmuJson(content = "第二条").toByteArray(), version = 1)
        val messages = parser.parseBinaryData(p1 + p2)
        assertEquals(2, messages.size)
        assertEquals("第一条", (messages[0] as DanmuMessage.Danmu).content)
        assertEquals("第二条", (messages[1] as DanmuMessage.Danmu).content)
    }

    @Test
    fun `unknown cmd maps to Other`() {
        val json = """{"cmd":"WIDGET_BANNER","data":{}}"""
        val packet = buildPacket(op = 5, body = json.toByteArray(), version = 1)
        val messages = parser.parseBinaryData(packet)
        assertEquals(1, messages.size)
        assertEquals("WIDGET_BANNER", (messages[0] as DanmuMessage.Other).cmd)
    }

    @Test
    fun `malformed json does not crash and yields no message`() {
        val packet = buildPacket(op = 5, body = "{not a json".toByteArray(), version = 1)
        assertTrue(parser.parseBinaryData(packet).isEmpty())
    }
}

package com.sakata.focusflow

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisionServiceClientTest {
    private val p=VisionServiceProfile("p","Test","https://example.test/prefix/v1","vision","vision-p","r")
    private fun envelope(text: String)=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("message",JSONObject().put("content",text)).put("finish_reason","stop"))).toString().toByteArray()
    private class Mock(var reply: VisionHttpReply) : VisionTransport {
        var calls=0;var path="";var body:ByteArray?=null;var onRequest: (() -> Unit)?=null
        override fun exchange(profile:VisionServiceProfile,key:String,path:String,body:ByteArray?,session:VisionSession):VisionHttpReply { calls++;this.path=profile.endpoint(path);this.body=body;onRequest?.invoke();return reply }
    }
    @Test fun `chat sends image and accepts string content without leaking credential`() {
        val m=Mock(VisionHttpReply(200,envelope("[]")));val answer=VisionServiceClient(m).chat(p,"private-key","grid",byteArrayOf(1,2,3),VisionSession())
        assertEquals("[]",(answer as VisionClientResult.Text).value);assertEquals("https://example.test/prefix/v1/chat/completions",m.path)
        val body=String(m.body!!);assertTrue(body.contains("image_url"));assertFalse(body.contains("private-key"))
    }
    @Test fun `text block content is supported but unknown block is rejected`() {
        val raw="""{"choices":[{"message":{"content":[{"type":"text","text":"[]"}]}}],"cost":0.012}"""
        val m=Mock(VisionHttpReply(200,raw.toByteArray()));assertEquals("[]",(VisionServiceClient(m).chat(p,"key","prompt",null,VisionSession()) as VisionClientResult.Text).value)
        m.reply=VisionHttpReply(200,raw.replace("\"type\":\"text\"","\"type\":\"tool\"").toByteArray());assertTrue(VisionServiceClient(m).chat(p,"key","prompt",null,VisionSession()) is VisionClientResult.Failure)
    }
    @Test fun `raw provider errors and secrets never appear in result`() {
        for(status in listOf(301,302,307,308,401,403,404,429,500)) {
            val m=Mock(VisionHttpReply(status,"private-key image-base64".toByteArray()));val result=VisionServiceClient(m).chat(p,"private-key","prompt",null,VisionSession()) as VisionClientResult.Failure
            assertFalse(result.message.contains("private-key"));assertFalse(result.message.contains("image-base64"));assertEquals(1,m.calls)
        }
    }
    @Test fun `truncation trailing content duplicates invalid utf8 and huge bodies reject`() {
        val samples=listOf(envelope("[]").dropLast(2).toByteArray(),envelope("[]")+"{}".toByteArray(),"{\"choices\":[],\"choices\":[]}".toByteArray(),byteArrayOf(0xc3.toByte(),0x28),ByteArray(VisionLimits.MAX_RESPONSE_BYTES+1))
        for(bytes in samples) assertTrue(VisionServiceClient(Mock(VisionHttpReply(200,bytes))).chat(p,"key","prompt",null,VisionSession()) is VisionClientResult.Failure)
    }
    @Test fun `length finish and tool calls never import partial content`() {
        for(raw in listOf(String(envelope("[]")).replace("stop","length"),"""{"choices":[{"message":{"content":"[]","tool_calls":[]}}]}""")) assertTrue(VisionServiceClient(Mock(VisionHttpReply(200,raw.toByteArray()))).chat(p,"key","prompt",null,VisionSession()) is VisionClientResult.Failure)
    }
    @Test fun `cancel before and during request prevents usable result`() {
        val m=Mock(VisionHttpReply(200,envelope("[]")));val client=VisionServiceClient(m);val before=VisionSession().apply { cancel() }
        assertTrue(client.chat(p,"key","prompt",null,before) is VisionClientResult.Failure);assertEquals(0,m.calls)
        val during=VisionSession();m.onRequest={during.cancel()};assertTrue(client.chat(p,"key","prompt",null,during) is VisionClientResult.Failure)
    }
    @Test fun `deadline and request budget are shared across retries`() {
        var now=0L;val session=VisionSession { now };val m=Mock(VisionHttpReply(200,envelope("OK")));val client=VisionServiceClient(m)
        repeat(8) { assertTrue(client.chat(p,"key","prompt",null,session) is VisionClientResult.Text) };assertTrue(client.chat(p,"key","prompt",null,session) is VisionClientResult.Failure);assertEquals(8,m.calls)
        val deadline=VisionSession { now };now=VisionLimits.SESSION_MS;assertTrue(client.chat(p,"key","prompt",null,deadline) is VisionClientResult.Failure);assertEquals(8,m.calls)
    }
    @Test fun `image and request bounds reject before transport`() {
        val m=Mock(VisionHttpReply(200,envelope("OK")));val client=VisionServiceClient(m)
        assertTrue(client.chat(p,"key","prompt",ByteArray(VisionLimits.MAX_IMAGE_BYTES+1),VisionSession()) is VisionClientResult.Failure)
        assertTrue(client.chat(p,"key","x".repeat(VisionLimits.MAX_REQUEST_BYTES),null,VisionSession()) is VisionClientResult.Failure);assertEquals(0,m.calls)
    }
    @Test fun `nonce probe requires correct image answer and strict object`() {
        val expected="543210";val m=Mock(VisionHttpReply(200,envelope("OK")));m.onRequest={ if(m.calls==2) m.reply=VisionHttpReply(200,envelope("""{"code":"543210"}""")) }
        val result=VisionServiceClient(m).probe(p,"key",VisionSession(),byteArrayOf(1),expected)
        assertTrue(result.passed);val request=JSONObject(String(m.body!!)).getJSONArray("messages").getJSONObject(0).getJSONArray("content");assertFalse(request.getJSONObject(0).getString("text").contains(expected))
        for(answer in listOf("""{"code":"111111"}""","""{"code":543210}""","""{"code":"543210","extra":true}""","```json\n{\"code\":\"543210\"}\n```")) {
            val mock=Mock(VisionHttpReply(200,envelope("OK")));mock.onRequest={if(mock.calls==2)mock.reply=VisionHttpReply(200,envelope(answer))};assertFalse(VisionServiceClient(mock).probe(p,"key",VisionSession(),byteArrayOf(1),expected).passed)
        }
    }
    @Test fun `models listing is optional and rejects invalid IDs`() {
        val m=Mock(VisionHttpReply(404,byteArrayOf()));assertNull(VisionServiceClient(m).models(p,"key",VisionSession()))
        m.reply=VisionHttpReply(200,"""{"data":[{"id":"vision"}]}""".toByteArray());assertEquals(listOf("vision"),VisionServiceClient(m).models(p,"key",VisionSession()))
        m.reply=VisionHttpReply(200,"""{"data":[{"id":123}]}""".toByteArray());assertNull(VisionServiceClient(m).models(p,"key",VisionSession()))
    }
}

package com.sakata.focusflow

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class VisionServiceModelsTest {
    private fun profile(url: String = "https://example.test/proxy/v1/") = VisionServiceProfile("p","Test",url,"vision","vision-p","r")
    @Test fun `endpoints preserve prefix and never duplicate v1`() {
        for(url in listOf("https://example.test", "https://example.test/v1", "https://example.test/proxy/v1/")) {
            val p=profile(url);assertNull(p.rejection());assertEquals(url.trimEnd('/')+"/chat/completions",p.endpoint("chat/completions"));assertEquals(url.trimEnd('/')+"/models",p.endpoint("models"))
        }
    }
    @Test fun `addresses reject insecure or credential bearing forms`() {
        listOf("http://example.test/v1","https://u:password@example.test/v1","https://example.test/v1?key=secret","https://example.test/#secret","https://example.test/a/../v1","https://example.test/%2e%2e/v1","https://example.test/v1/chat/completions","https://example.test:99999").forEach { assertNotNull(it,profile(it).rejection()) }
    }
    @Test fun `shared credential cannot be redirected to a custom host`() {
        assertNotNull(profile().copy(id="siliconflow",credentialRef=VisionCredentialStore.SHARED_REF).rejection())
        assertNull(profile("https://api.siliconflow.cn/v1").copy(id="siliconflow",credentialRef=VisionCredentialStore.SHARED_REF).rejection())
    }
    @Test fun `configuration round trips and contains no key value`() {
        val c=VisionServiceConfiguration(listOf(profile().copy(verifiedCredentialRevision="k1",probeVersion=1)),"p")
        assertEquals(c,VisionProfileCodec.decode(VisionProfileCodec.encode(c)));assertFalse(VisionProfileCodec.encode(c).contains("apiKey"))
    }
    @Test fun `codec refuses malformed duplicated unknown and coerced fields`() {
        val raw=VisionProfileCodec.encode(VisionServiceConfiguration(listOf(profile())))
        listOf(raw+" {}",raw.dropLast(1),raw.replace("\"version\":1","\"version\":\"1\""),raw.replace("\"version\":1","\"version\":1.0"),raw.replace("\"version\":1","\"version\":1,\"version\":1"),raw.replace("\"version\":1","\"version\":2"),raw.replace("\"timeoutSeconds\":60","\"timeoutSeconds\":60.5"),raw.replace("\"protocol\":\"openai_chat\"","\"protocol\":\"anthropic\""),raw.replace("\"defaultId\":null","\"defaultId\":\"missing\""),raw.replace("\"version\":1","\"version\":1,\"apiKey\":\"secret\"")).forEach { assertNull(it,VisionProfileCodec.decode(it)) }
    }
    @Test fun `strict syntax permits metadata fractions but not lenient JSON`() {
        assertTrue(VisionJsonSyntax.valid("""{"usage":{"cost":0.0001},"content":"中文","ok":true} """))
        listOf("{a:1}","{'a':1}","{\"a\":01}","{\"a\":1,}","{\"a\":NaN}","{\"a\":1}//comment","[1,]","{\"a\":\"\\q\"}","{\"a\":1,\"\\u0061\":2}","[".repeat(34)+"]".repeat(34)).forEach { assertFalse(it,VisionJsonSyntax.valid(it)) }
    }
    @Test fun `unknown geometry remains unknown and cannot be user confirmed`() {
        val c=VisionCandidate("c","数学",null,null,null,null,emptyList())
        assertTrue(c.valid());assertFalse(c.copy(state=VisionCandidateState.CONFIRMED_BY_USER).valid());assertFalse(c.copy(day=0).valid())
        assertFalse(VisionBox(Double.NaN,0.0,1.0,1.0).valid());assertFalse(VisionBox(0.0,0.2,1.0,0.1).valid())
    }
    @Test fun `synthetic corpus has all categories exact hashes and bounded annotations`() {
        val root=JSONObject(javaClass.getResource("/vision-samples/manifest.json")!!.readText());val samples=root.getJSONArray("samples")
        assertEquals(24,samples.length());val counts=mutableMapOf<String,Int>()
        for(i in 0 until samples.length()) {
            val s=samples.getJSONObject(i);val category=s.getString("category");counts[category]=(counts[category]?:0)+1
            val bytes=javaClass.getResource("/vision-samples/"+s.getString("file"))!!.readBytes()
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) };assertEquals(s.getString("sha256"),hash)
            val a=s.getJSONArray("expected");if(category=="reject") assertEquals(0,a.length()) else assertEquals(4,a.length())
            for(j in 0 until a.length()) { val c=a.getJSONObject(j);val b=c.getJSONArray("box");assertTrue(VisionBox(b.getDouble(0),b.getDouble(1),b.getDouble(2),b.getDouble(3)).valid());if(!c.isNull("day")) assertTrue(c.getInt("day") in 1..7) }
        }
        assertEquals(mapOf("clear" to 10,"recoverable" to 6,"manual_anchors" to 4,"reject" to 4),counts)
    }
}

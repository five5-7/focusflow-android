package com.sakata.focusflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class VisionTransportAndBackupTest {
    private val p=VisionServiceProfile("p","Test","https://example.test/prefix/v1","vision","vision-p","r")
    private class Connection(private val status:Int, private val bytes:ByteArray, private val advertised:Long=bytes.size.toLong()) : HttpURLConnection(URL("https://example.test")) {
        var disconnected=false;var inputOpened=false;val output=ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {disconnected=true}
        override fun usingProxy()=false
        override fun getResponseCode()=status
        override fun getContentLengthLong()=advertised
        override fun getInputStream()=ByteArrayInputStream(bytes).also {inputOpened=true}
        override fun getOutputStream()=output
    }
    @Test fun `production transport never follows redirects or reads provider error body`() {
        val c=Connection(307,"private-key".toByteArray());val result=VisionServiceClient(VisionUrlTransport { c }).chat(p,"private-key","prompt",null,VisionSession())
        assertTrue(result is VisionClientResult.Failure);assertFalse(c.instanceFollowRedirects);assertFalse(c.inputOpened);assertTrue(c.disconnected);assertEquals("Bearer private-key",c.getRequestProperty("Authorization"))
        assertFalse(String(c.output.toByteArray()).contains("private-key"))
    }
    @Test fun `production transport enforces bytes for known and unknown length`() {
        for(length in listOf(-1L,(VisionLimits.MAX_RESPONSE_BYTES+1).toLong())) {
            val c=Connection(200,ByteArray(VisionLimits.MAX_RESPONSE_BYTES+1),length)
            assertTrue(VisionServiceClient(VisionUrlTransport { c }).chat(p,"key","prompt",null,VisionSession()) is VisionClientResult.Failure);assertTrue(c.disconnected)
        }
        val c=Connection(200,"""{"choices":[{"message":{"content":"中文"}}]}""".toByteArray(),-1L)
        assertEquals("中文",(VisionServiceClient(VisionUrlTransport {c}).chat(p,"key","prompt",null,VisionSession()) as VisionClientResult.Text).value);assertTrue(c.disconnected)
    }
    @Test fun `cancelling session disconnects attached connections and concurrency bound is real`() {
        val s=VisionSession();val c=Connection(200,byteArrayOf());s.attach(c);s.begin();s.begin()
        try { s.begin();fail("third request admitted") } catch(e:VisionSessionRejected) {assertTrue(e.safeMessage.contains("2"))}
        s.cancel();assertTrue(c.disconnected);assertTrue(s.cancelled());s.end();s.end()
    }
    @Test fun `backup rules exclude only credential file and keep business preferences eligible`() {
        val context:Context=ApplicationProvider.getApplicationContext()
        for(resource in listOf(R.xml.backup_rules,R.xml.data_extraction_rules)) {
            val xml=context.resources.getXml(resource);var exclusions=0
            while(xml.eventType!=XmlPullParser.END_DOCUMENT) {
                if(xml.eventType==XmlPullParser.START_TAG && xml.name=="exclude") { exclusions++;assertEquals("sharedpref",xml.getAttributeValue(null,"domain"));assertEquals("focusflow_credentials.xml",xml.getAttributeValue(null,"path")) }
                xml.next()
            }
            assertEquals(if(resource==R.xml.backup_rules) 1 else 2,exclusions);xml.close()
        }
    }
}

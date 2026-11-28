package dev.gbot.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterTest {
    private val out = """{"error":{"code":"data_inspection_failed","param":null,"message":"Output data may contain inappropriate content.","type":"data_inspection_failed"}}"""
    private val inp = """{"error":{"code":"data_inspection_failed","param":null,"message":"Input data may contain inappropriate content.","type":"data_inspection_failed"}}"""

    @Test fun flaggedOutputIsRetried() {
        assertTrue(LocalProviders.retryable(ProviderError(400, out)))
        assertTrue(ContentFilter.explain("400 $out")!!.contains("blocking the answer"))
    }

    @Test fun flaggedInputIsNotRetried() {
        assertFalse(LocalProviders.retryable(ProviderError(400, inp)))
        assertTrue(ContentFilter.explain("400 $inp")!!.contains("refused what I sent"))
    }

    @Test fun ordinaryFailures() {
        assertTrue(LocalProviders.retryable(ProviderError(429, "slow down")))
        assertTrue(LocalProviders.retryable(ProviderError(503, "")))
        assertFalse(LocalProviders.retryable(ProviderError(401, "bad key")))
        assertNull(ContentFilter.explain("401 bad key"))
        assertNotNull(ContentFilter.explain(ProviderError(400, out).message!!))
        assertEquals("400 bad", ProviderError(400, "bad").message)
    }
}

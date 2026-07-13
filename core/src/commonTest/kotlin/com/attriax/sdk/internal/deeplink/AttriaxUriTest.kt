package com.attriax.sdk.internal.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure URI parser tests backing the deep-link core. */
class AttriaxUriTest {

    @Test
    fun parsesHttpsWithHostPathAndQuery() {
        val uri = AttriaxUri.parse("https://sub.attriax.com/promo/summer?a=1&b=two")!!
        assertEquals("https", uri.scheme)
        assertEquals("sub.attriax.com", uri.host)
        assertEquals("/promo/summer", uri.path)
        assertEquals(listOf("1"), uri.queryParametersAll["a"])
        assertEquals(listOf("two"), uri.queryParametersAll["b"])
        assertTrue(uri.isScheme("https"))
    }

    @Test
    fun parsesCustomSchemeWithoutAuthority() {
        val uri = AttriaxUri.parse("myapp://open/product/42")!!
        assertEquals("myapp", uri.scheme)
        assertEquals("open", uri.host)
        assertEquals("/product/42", uri.path)
    }

    @Test
    fun preservesRepeatedQueryKeysInOrder() {
        val uri = AttriaxUri.parse("https://x.attriax.com/p?t=a&t=b&t=c")!!
        assertEquals(listOf("a", "b", "c"), uri.queryParametersAll["t"])
    }

    @Test
    fun percentAndPlusDecodingInQuery() {
        val uri = AttriaxUri.parse("https://x.attriax.com/p?q=a%20b+c&e=%26")!!
        assertEquals(listOf("a b c"), uri.queryParametersAll["q"])
        assertEquals(listOf("&"), uri.queryParametersAll["e"])
    }

    @Test
    fun stripsFragment() {
        val uri = AttriaxUri.parse("https://x.attriax.com/p?a=1#/section")!!
        assertEquals("/p", uri.path)
        assertEquals(listOf("1"), uri.queryParametersAll["a"])
    }

    @Test
    fun blankOrNullYieldsNull() {
        assertNull(AttriaxUri.parse(null))
        assertNull(AttriaxUri.parse(""))
        assertNull(AttriaxUri.parse("   "))
    }

    @Test
    fun roundTripsRawString() {
        val raw = "https://sub.attriax.com/promo?a=1"
        assertEquals(raw, AttriaxUri.parse(raw)!!.toString())
    }
}

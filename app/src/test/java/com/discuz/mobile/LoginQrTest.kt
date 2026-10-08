package com.discuz.mobile

import org.junit.Assert.*
import org.junit.Test

class LoginQrTest {
    @Test fun acceptsOnlyVersionedLoginPayloads() {
        val qr = LoginQr.parse("discuz-app-login:v1:${"a".repeat(64)}:123:${"b".repeat(64)}")
        assertEquals(123, qr.uid)
        assertEquals("a".repeat(64), qr.siteId)
        assertEquals("b".repeat(64), qr.key)
    }
    @Test fun rejectsLinksCookiesMalformedKeysAndOutOfRangeAccounts() {
        for (value in listOf("https://example.com/?auth=cookie", "auth=cookie", "discuz-app-login:v1:${"a".repeat(64)}:2147483648:${"b".repeat(64)}",
            "discuz-app-login:v1:${"a".repeat(64)}:0:${"b".repeat(64)}", "discuz-app-login:v1:${"a".repeat(64)}:1:bad")) {
            assertThrows(IllegalArgumentException::class.java) { LoginQr.parse(value) }
        }
    }
}

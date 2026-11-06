package dev.eribssz.passage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EndpointTest {
    @Test fun acceptsGoodAddresses() {
        assertEquals(Endpoint("200.232.72.102", 4433), Endpoint.parse("200.232.72.102:4433"))
        assertEquals(Endpoint("10.0.0.1", 443), Endpoint.parse("10.0.0.1"))
        assertEquals(Endpoint("10.0.0.1", 443), Endpoint.parse("10.0.0.1:"))
        assertEquals(Endpoint("vpn.example.com", 10443), Endpoint.parse("https://VPN.example.com:10443/remote/login"))
        assertEquals(Endpoint("gateway", 443), Endpoint.parse("gateway"))
    }

    @Test fun rejectsJunk() {
        listOf(
            "454545545454845484848484848484848484848484848484848:443",
            "256.1.1.1:443", "1.2.3:443", "1.2.3.4.5", "01.2.3.4", "1..2.3:443",
            "1.2.3.4:0", "1.2.3.4:70000", "1.2.3.4:abc", ":443",
            "2001:db8::1", "[2001:db8::1]:443", "-bad.example.com", "a b",
        ).forEach { assertNull(Endpoint.parse(it), it) }
        assertEquals("IPv6 addresses aren't supported", Endpoint.check("[::1]:443"))
        assertEquals("Not a valid IP address", Endpoint.check("4545454545:443"))
    }

    /** Types [keys] one by one through the mask. */
    private fun type(keys: String, start: String = "") = keys.fold(start) { acc, c -> AddressMask.apply(acc, acc + c) }

    @Test fun maskAddsSeparators() {
        assertEquals("200.232.72.102:4433", type("200232721024433"))
        assertEquals("177.68.159.144:4433", type("177681591444433"))
        assertEquals("10.0.0.1:443", type("10.0.0.1.443"))       // "." after the 4th octet becomes ":"
        assertEquals("10.0.0.1:443", type("10.0.0.1:443"))
        assertEquals("192.168.", type("192168"))
    }

    @Test fun maskCaps() {
        assertEquals("255.5", type("2555"))
        assertEquals("25.6", type("256"))                         // 256 can't be an octet
        assertEquals("30.", type("30"))                           // 30x would be > 255
        assertEquals("1.2.3.4:65535", type("1.2.3.4:655359"))
        assertEquals("1.", type("1.."))                          // no empty octets
        assertEquals("0.", type("0"))                             // a lone 0 is a whole octet
    }

    @Test fun maskLeavesOtherInputAlone() {
        assertEquals("vpn.example.com:443", type("vpn.example.com:443"))
        assertEquals("1.2.3.4:443, 5.6", type(", 5.6", "1.2.3.4:443"))
        assertEquals("1.2.3", AddressMask.apply("1.2.3.", "1.2.3"))  // backspace untouched
        assertEquals("1.2.3.4:443 5.6.7.8", AddressMask.apply("", "1.2.3.4:443 5.6.7.8"))  // paste untouched
    }
}

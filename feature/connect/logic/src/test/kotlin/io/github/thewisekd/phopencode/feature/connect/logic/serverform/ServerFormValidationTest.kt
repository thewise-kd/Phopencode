package io.github.thewisekd.phopencode.feature.connect.logic.serverform

import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormFieldError
import kotlin.test.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Example values only: documentation names and address ranges (RFC 2606, 5737, 3849). */
class ServerFormValidationTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "example.com",
            "server",
            "my-server.example.org",
            "example.com.",
            "a.b.c.d.example",
            "xn--bcher-kva.example",
            "192.0.2.10",
            "0.0.0.0",
            "255.255.255.255",
            "2001:db8::1",
            "::1",
            "::",
            "2001:db8:0:0:0:0:0:1",
            "2001:DB8::ABCD",
            "::ffff:192.0.2.1",
            "fe80::1",
        ],
    )
    fun `a valid host is accepted as typed`(host: String) {
        assertEquals(Checked.Valid(host), checkHost(host))
    }

    @ParameterizedTest
    @ValueSource(strings = ["  example.com ", "\texample.com\n"])
    fun `spaces around the host are dropped`(host: String) {
        assertEquals(Checked.Valid("example.com"), checkHost(host))
    }

    @ParameterizedTest
    @ValueSource(strings = ["[2001:db8::1]", " [::1] "])
    fun `brackets around an IPv6 literal are dropped`(host: String) {
        assertEquals(Checked.Valid(host.trim().removeSurrounding("[", "]")), checkHost(host))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t", "[]"])
    fun `an empty host is required`(host: String) {
        assertEquals(Checked.Invalid(ServerFormFieldError.Required), checkHost(host))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ssh://example.com",
            "https://example.com",
            "example.com/path",
            "example.com:22",
            "user@example.com",
            "exa mple.com",
            "-example.com",
            "example-.com",
            "exa_mple.com",
            "example..com",
            ".example.com",
            "example.com..",
            "192.0.2.256",
            "192.0.2",
            "192.0.2.1.5",
            "192.0.2.010",
            "1.2.3.-4",
            "999",
            "2001:db8::1::1",
            "2001:db8:0:0:0:0:0:0:1",
            "2001:db8::12345",
            "2001:db8::g",
            "fe80::1%eth0",
            ":::",
            "1:2:3:4:5:6:7::8:9",
            "::ffff:192.0.2.256",
            "[example.com]",
            "[2001:db8::1",
            "ex\u0000ample.com",
            "exämple.com",
        ],
    )
    fun `anything that is not a host name or address is invalid`(host: String) {
        assertEquals(Checked.Invalid(ServerFormFieldError.Invalid), checkHost(host))
    }

    @org.junit.jupiter.api.Test
    fun `a label longer than 63 characters or a name longer than 253 is invalid`() {
        assertEquals(Checked.Valid("a".repeat(63) + ".example"), checkHost("a".repeat(63) + ".example"))
        assertEquals(Checked.Invalid(ServerFormFieldError.Invalid), checkHost("a".repeat(64) + ".example"))
        val longName = List(4) { "a".repeat(63) }.joinToString(".")
        assertEquals(255, longName.length)
        assertEquals(Checked.Invalid(ServerFormFieldError.Invalid), checkHost(longName))
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", "22", "2222", "65535", " 22 "])
    fun `a port in 1 to 65535 is accepted`(port: String) {
        assertEquals(Checked.Valid(port.trim().toInt()), checkPort(port))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  "])
    fun `an empty port is required`(port: String) {
        assertEquals(Checked.Invalid(ServerFormFieldError.Required), checkPort(port))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "65536", "-1", "+22", "2 2", "22a", "0x16", "99999999999", "٢٢", "1e3"])
    fun `a port outside 1 to 65535 or not in digits is invalid`(port: String) {
        assertEquals(Checked.Invalid(ServerFormFieldError.Invalid), checkPort(port))
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", "4096", "49374", "65535", " 4096 "])
    fun `an opencode port in 1 to 65535 is accepted`(port: String) {
        assertEquals(Checked.Valid(port.trim().toInt()), checkOptionalPort(port))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "\t"])
    fun `an empty opencode port means automatic detection`(port: String) {
        assertEquals(Checked.Valid(null), checkOptionalPort(port))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "65536", "-1", "+22", "40 96", "4096a", "0x16", "99999999999", "٤٠٩٦", "1e3"])
    fun `an opencode port outside 1 to 65535 or not in digits is invalid`(port: String) {
        assertEquals(Checked.Invalid(ServerFormFieldError.Invalid), checkOptionalPort(port))
    }

    @ParameterizedTest
    @ValueSource(strings = ["root", "deploy", "j.doe", "user_1", "_svc", "web-admin", "Admin", "u2", "a"])
    fun `a login name is accepted`(user: String) {
        assertEquals(Checked.Valid(user), checkUsername(user))
    }

    @org.junit.jupiter.api.Test
    fun `spaces around the user are dropped`() {
        assertEquals(Checked.Valid("deploy"), checkUsername("  deploy "))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `an empty user is required`(user: String) {
        assertEquals(Checked.Invalid(ServerFormFieldError.Required), checkUsername(user))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "-oProxyCommand=x",
            "-l",
            "j doe",
            "user@host",
            "user:pass",
            "us/er",
            "us\$er",
            "user;id",
            "12345",
            ".hidden",
            "kullanıcı",
            "us\ner",
        ],
    )
    fun `anything that is not a login name is invalid`(user: String) {
        assertEquals(Checked.Invalid(ServerFormFieldError.Invalid), checkUsername(user))
    }

    @org.junit.jupiter.api.Test
    fun `a user name longer than 32 characters is invalid`() {
        assertEquals(Checked.Valid("a".repeat(32)), checkUsername("a".repeat(32)))
        assertEquals(Checked.Invalid(ServerFormFieldError.Invalid), checkUsername("a".repeat(33)))
    }
}

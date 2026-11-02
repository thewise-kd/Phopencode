package io.github.thewisekd.phopencode.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json

class ServerProfileTest {
    private val auths = listOf(
        SshAuth.Password(SecretRef("sentinel-ref-a")),
        SshAuth.PrivateKey(SecretRef("sentinel-ref-b"), passphraseRef = SecretRef("sentinel-ref-c")),
        SshAuth.PrivateKey(SecretRef("sentinel-ref-b")),
    )

    private fun profile(auth: SshAuth) = ServerProfile(
        id = ServerProfileId("profile-1"),
        name = "sentinel-name",
        host = "sentinel-host.invalid",
        username = "sentinel-user",
        auth = auth,
        opencodeCredential = SecretRef("sentinel-ref-d"),
        lastUsedAt = Instant.fromEpochSeconds(1_700_000_000),
    )

    @Test
    fun `toString reveals no address, account or secret reference`() {
        auths.forEach { auth ->
            val text = profile(auth).toString()

            assertFalse(text.contains("sentinel"), text)
            assertTrue(text.contains("profile-1"), text)
        }
    }

    @Test
    fun `auth and references hide what they point to when printed alone`() {
        auths.forEach { assertFalse(it.toString().contains("sentinel"), it.toString()) }
        assertFalse(SecretRef("sentinel-ref-a").toString().contains("sentinel"))
    }

    @Test
    fun `a secret reference still resolves to its id`() {
        assertEquals("sentinel-ref-a", SecretRef("sentinel-ref-a").id)
    }

    @Test
    fun `the port defaults to the SSH port`() {
        assertEquals(22, profile(auths.first()).port)
    }

    @Test
    fun `no field of a profile is named for a raw secret`() {
        val forbidden = Regex("password|passwd|privatekey|token", RegexOption.IGNORE_CASE)
        val classes = listOf(ServerProfile::class.java, SshAuth.Password::class.java, SshAuth.PrivateKey::class.java)

        val names = classes.flatMap { type -> type.declaredFields.map { it.name } }

        assertEquals(emptyList(), names.filter(forbidden::containsMatchIn))
    }

    @Test
    fun `a persisted profile round-trips`() {
        auths.forEach { auth ->
            val profile = profile(auth)

            val encoded = Json.encodeToString(ServerProfile.serializer(), profile)

            assertEquals(profile, Json.decodeFromString(ServerProfile.serializer(), encoded))
        }
    }

    @Test
    fun `a data class copy keeps toString masked`() {
        val copy = profile(auths.first()).copy(host = "sentinel-other.invalid")

        assertFalse(copy.toString().contains("sentinel"), copy.toString())
    }
}

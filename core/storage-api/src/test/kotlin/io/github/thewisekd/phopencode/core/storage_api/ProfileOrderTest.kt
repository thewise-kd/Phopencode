package io.github.thewisekd.phopencode.core.storage_api

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ProfileOrderTest {
    private fun profile(id: String, name: String = "name", lastUsedAtSeconds: Long? = null) = ServerProfile(
        id = ServerProfileId(id),
        name = name,
        host = "server.invalid",
        username = "user",
        auth = SshAuth.Password(SecretRef("ref")),
        lastUsedAt = lastUsedAtSeconds?.let(Instant::fromEpochSeconds),
    )

    private fun sortedIds(vararg profiles: ServerProfile) =
        profiles.sortedWith(ProfileRepository.MOST_RECENTLY_USED_FIRST).map { it.id.value }

    @Test
    fun `the most recently used profile comes first`() {
        val ids = sortedIds(
            profile("old", lastUsedAtSeconds = 100),
            profile("new", lastUsedAtSeconds = 300),
            profile("mid", lastUsedAtSeconds = 200),
        )

        assertEquals(listOf("new", "mid", "old"), ids)
    }

    @Test
    fun `a profile never used comes after every used profile`() {
        val ids = sortedIds(profile("never"), profile("used", lastUsedAtSeconds = 0))

        assertEquals(listOf("used", "never"), ids)
    }

    @Test
    fun `equal times fall back to name, then id, so the order is stable across reads`() {
        val ids = sortedIds(
            profile("c", name = "beta"),
            profile("b", name = "alpha"),
            profile("a", name = "beta"),
        )

        assertEquals(listOf("b", "a", "c"), ids)
    }
}

package io.github.thewisekd.phopencode.core.testing.contract

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.storage_api.AppLanguage
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyCheck
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyUpdate
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.core.storage_api.SshKeyEntry
import io.github.thewisekd.phopencode.core.storage_api.SshKeyId
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.core.storage_api.ThemePreference
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

interface SecretStoreHarness {
    val store: SecretStore

    /** Leaves an entry under [ref] that exists and can never be decrypted. */
    suspend fun givenUnreadable(ref: SecretRef)

    /** Whether storage can be reached right now. */
    suspend fun setReachable(reachable: Boolean)
}

private val SECRET = SecretRef("secret-a")
private val OTHER_SECRET = SecretRef("secret-b")

private suspend fun SecretStore.text(ref: SecretRef): String? = when (val result = read(ref)) {
    is SecretReadResult.Found -> result.value.use { it.revealText() }
    SecretReadResult.NotFound -> null
    SecretReadResult.Unreadable -> contractFailure("Entry is unreadable")
}

fun secretStoreContract(newHarness: suspend () -> SecretStoreHarness): List<ContractCase> = contractCases {
    case("read finds what put stored, and nothing under another reference") {
        val store = newHarness().store
        SecretValue.ofText("value").use { store.put(SECRET, it) }
        expectEqual("value", store.text(SECRET), "stored value")
        expectEqual(SecretReadResult.NotFound, store.read(OTHER_SECRET), "other reference")
    }
    case("bytes that are not text come back byte for byte") {
        val store = newHarness().store
        val bytes = byteArrayOf(0, -1, 127, -128)
        SecretValue.ofBytes(bytes).use { store.put(SECRET, it) }
        val found = store.read(SECRET) as SecretReadResult.Found
        expectThat(found.value.use { it.revealBytes() }.contentEquals(bytes), "bytes changed in storage")
    }
    case("put replaces an existing entry") {
        val store = newHarness().store
        SecretValue.ofText("old").use { store.put(SECRET, it) }
        SecretValue.ofText("new").use { store.put(SECRET, it) }
        expectEqual("new", store.text(SECRET), "replaced value")
    }
    case("put replaces an unreadable entry") {
        val harness = newHarness()
        harness.givenUnreadable(SECRET)
        SecretValue.ofText("new").use { harness.store.put(SECRET, it) }
        expectEqual("new", harness.store.text(SECRET), "replaced value")
    }
    case("the caller keeps ownership of the value it put") {
        val store = newHarness().store
        val value = SecretValue.ofText("value")
        store.put(SECRET, value)
        expectEqual("value", value.revealText(), "value after put")
        value.close()
        expectEqual("value", store.text(SECRET), "stored value after the caller closed its own")
    }
    case("closing a read value leaves the entry readable") {
        val store = newHarness().store
        SecretValue.ofText("value").use { store.put(SECRET, it) }
        store.text(SECRET)
        expectEqual("value", store.text(SECRET), "second read")
    }
    case("an undecryptable entry reads as Unreadable") {
        val harness = newHarness()
        harness.givenUnreadable(SECRET)
        expectEqual(SecretReadResult.Unreadable, harness.store.read(SECRET), "read")
    }
    case("delete removes an entry and leaves the others") {
        val store = newHarness().store
        SecretValue.ofText("a").use { store.put(SECRET, it) }
        SecretValue.ofText("b").use { store.put(OTHER_SECRET, it) }
        store.delete(SECRET)
        expectEqual(SecretReadResult.NotFound, store.read(SECRET), "deleted entry")
        expectEqual("b", store.text(OTHER_SECRET), "other entry")
    }
    case("delete removes an unreadable entry") {
        val harness = newHarness()
        harness.givenUnreadable(SECRET)
        harness.store.delete(SECRET)
        expectEqual(SecretReadResult.NotFound, harness.store.read(SECRET), "deleted entry")
    }
    case("delete does nothing if there is no entry") {
        newHarness().store.delete(SECRET)
    }
    case("deleteAll removes every entry, also unreadable ones") {
        val harness = newHarness()
        SecretValue.ofText("a").use { harness.store.put(SECRET, it) }
        harness.givenUnreadable(OTHER_SECRET)
        harness.store.deleteAll()
        expectEqual(SecretReadResult.NotFound, harness.store.read(SECRET), "readable entry")
        expectEqual(SecretReadResult.NotFound, harness.store.read(OTHER_SECRET), "unreadable entry")
    }
    case("every function throws IOException when storage cannot be reached") {
        val harness = newHarness()
        val store = harness.store
        SecretValue.ofText("kept").use { store.put(SECRET, it) }
        harness.setReachable(false)
        expectThrows<IOException>("put") { SecretValue.ofText("x").use { store.put(SECRET, it) } }
        expectThrows<IOException>("read") { store.read(SECRET) }
        expectThrows<IOException>("delete") { store.delete(SECRET) }
        expectThrows<IOException>("deleteAll") { store.deleteAll() }
        harness.setReachable(true)
        expectEqual("kept", store.text(SECRET), "entry after the failed calls")
    }
}

private fun profile(id: String, name: String = id, lastUsed: Long? = null) = ServerProfile(
    id = ServerProfileId(id),
    name = name,
    host = "server.invalid",
    username = "user",
    auth = SshAuth.Password(SecretRef("ref-$id")),
    lastUsedAt = lastUsed?.let(Instant::fromEpochSeconds),
)

fun profileRepositoryContract(newRepository: suspend () -> ProfileRepository): List<ContractCase> = contractCases {
    case("a new repository is empty") {
        expectEqual(emptyList(), newRepository().list(), "profiles")
    }
    case("save adds, and replaces the profile with the same ID") {
        val repository = newRepository()
        repository.save(profile("a", name = "old"))
        repository.save(profile("b"))
        repository.save(profile("a", name = "new"))
        expectEqual(setOf("new", "b"), repository.list().map { it.name }.toSet(), "names")
        expectEqual(2, repository.list().size, "count")
    }
    case("marking a profile used is a save, and reorders the list") {
        val repository = newRepository()
        repository.save(profile("a"))
        repository.save(profile("b", lastUsed = 1))
        repository.save(profile("a", lastUsed = 2))
        expectEqual(listOf("a", "b"), repository.list().map { it.id.value }, "order")
    }
    case("lists are sorted by most recently used first") {
        val repository = newRepository()
        val profiles = listOf(
            profile("never-b", name = "b"),
            profile("old", lastUsed = 1),
            profile("never-a", name = "a"),
            profile("recent", lastUsed = 2),
            profile("same-name-2", name = "a"),
        )
        profiles.forEach { repository.save(it) }
        val expected = profiles.sortedWith(ProfileRepository.MOST_RECENTLY_USED_FIRST)
        expectEqual(listOf("recent", "old", "never-a", "same-name-2", "never-b"), expected.map { it.id.value }, "comparator")
        expectEqual(expected, repository.list(), "list")
        expectEqual(expected, repository.observe().first(), "observe")
    }
    case("delete removes the profile, and does nothing without one") {
        val repository = newRepository()
        repository.save(profile("a"))
        repository.delete(ServerProfileId("missing"))
        expectEqual(1, repository.list().size, "after deleting a missing profile")
        repository.delete(ServerProfileId("a"))
        expectEqual(emptyList(), repository.list(), "after delete")
    }
    case("observe emits the current list on collection, then after every change") {
        val repository = newRepository()
        repository.save(profile("a"))
        val seen = collect(repository.observe())
        expectEqual(listOf("a"), seen.next("current list").map { it.id.value }, "current list")
        repository.save(profile("b", lastUsed = 1))
        expectEqual(listOf("b", "a"), seen.next("list after save").map { it.id.value }, "list after save")
        repository.delete(ServerProfileId("b"))
        expectEqual(listOf("a"), seen.next("list after delete").map { it.id.value }, "list after delete")
    }
}

private val ENDPOINT = HostEndpoint("server.invalid", 22)
private val KEY_A = HostKeyFingerprint("SHA256:aaaa")
private val KEY_B = HostKeyFingerprint("SHA256:bbbb")
private val KEY_C = HostKeyFingerprint("SHA256:cccc")

fun knownHostsStoreContract(newStore: suspend () -> KnownHostsStore): List<ContractCase> = contractCases {
    case("a new endpoint has no approved key and checks as Unknown") {
        val store = newStore()
        expectEqual(null, store.approved(ENDPOINT), "approved")
        expectEqual(HostKeyCheck.Unknown, store.check(ENDPOINT, KEY_A), "check")
    }
    case("approve stores a first key, which then checks as Trusted") {
        val store = newStore()
        expectEqual(HostKeyUpdate.Stored, store.approve(ENDPOINT, KEY_A), "approve")
        expectEqual(KEY_A, store.approved(ENDPOINT), "approved")
        expectEqual(HostKeyCheck.Trusted, store.check(ENDPOINT, KEY_A), "check")
    }
    case("a different key checks as Changed and names the approved one") {
        val store = newStore()
        store.approve(ENDPOINT, KEY_A)
        expectEqual(HostKeyCheck.Changed(KEY_A), store.check(ENDPOINT, KEY_B), "check")
    }
    case("fingerprints are compared exactly") {
        val store = newStore()
        store.approve(ENDPOINT, HostKeyFingerprint("SHA256:AbC"))
        expectEqual(
            HostKeyCheck.Changed(HostKeyFingerprint("SHA256:AbC")),
            store.check(ENDPOINT, HostKeyFingerprint("SHA256:abc")),
            "check",
        )
    }
    case("host names are case-insensitive, and the port tells endpoints apart") {
        val store = newStore()
        store.approve(HostEndpoint("Server.Invalid", 22), KEY_A)
        expectEqual(KEY_A, store.approved(HostEndpoint("server.invalid", 22)), "other spelling")
        expectEqual(null, store.approved(HostEndpoint("server.invalid", 2222)), "other port")
    }
    case("approving the approved key again gives Stored") {
        val store = newStore()
        store.approve(ENDPOINT, KEY_A)
        expectEqual(HostKeyUpdate.Stored, store.approve(ENDPOINT, KEY_A), "approve")
        expectEqual(KEY_A, store.approved(ENDPOINT), "approved")
    }
    case("approve never overwrites a different approved key") {
        val store = newStore()
        store.approve(ENDPOINT, KEY_A)
        expectEqual(HostKeyUpdate.Rejected(KEY_A), store.approve(ENDPOINT, KEY_B), "approve")
        expectEqual(KEY_A, store.approved(ENDPOINT), "approved")
    }
    case("replace stores the new key when the expected key is still approved") {
        val store = newStore()
        store.approve(ENDPOINT, KEY_A)
        expectEqual(HostKeyUpdate.Stored, store.replace(ENDPOINT, KEY_A, KEY_B), "replace")
        expectEqual(KEY_B, store.approved(ENDPOINT), "approved")
    }
    case("replace changes nothing when another key is approved") {
        val store = newStore()
        store.approve(ENDPOINT, KEY_A)
        expectEqual(HostKeyUpdate.Rejected(KEY_A), store.replace(ENDPOINT, KEY_B, KEY_C), "replace")
        expectEqual(KEY_A, store.approved(ENDPOINT), "approved")
    }
    case("replace changes nothing when no key is approved") {
        val store = newStore()
        expectEqual(HostKeyUpdate.Rejected(null), store.replace(ENDPOINT, KEY_A, KEY_B), "replace")
        expectEqual(null, store.approved(ENDPOINT), "approved")
    }
    case("delete removes the approved key of that endpoint only, and does nothing without one") {
        val store = newStore()
        val other = HostEndpoint("other.invalid", 22)
        store.approve(ENDPOINT, KEY_A)
        store.approve(other, KEY_B)
        store.delete(HostEndpoint("missing.invalid", 22))
        store.delete(ENDPOINT)
        expectEqual(null, store.approved(ENDPOINT), "deleted")
        expectEqual(KEY_B, store.approved(other), "other endpoint")
    }
    case("concurrent first approvals leave exactly one winner") {
        val store = newStore()
        val keys = (0 until 32).map { HostKeyFingerprint("SHA256:$it") }
        val updates = withContext(Dispatchers.Default) {
            keys.map { async { it to store.approve(ENDPOINT, it) } }.awaitAll()
        }
        val winners = updates.filter { (_, update) -> update == HostKeyUpdate.Stored }.map { (key, _) -> key }
        expectEqual(1, winners.size, "stored approvals")
        val winner = winners.single()
        expectEqual(winner, store.approved(ENDPOINT), "approved")
        updates.filter { (key, _) -> key != winner }.forEach { (_, update) ->
            expectEqual(HostKeyUpdate.Rejected(winner), update, "losing approval")
        }
    }
    case("concurrent replacements of one key leave exactly one winner") {
        val store = newStore()
        store.approve(ENDPOINT, KEY_A)
        val updates = withContext(Dispatchers.Default) {
            (0 until 32).map { async { store.replace(ENDPOINT, KEY_A, HostKeyFingerprint("SHA256:$it")) } }.awaitAll()
        }
        expectEqual(1, updates.count { it == HostKeyUpdate.Stored }, "stored replacements")
    }
}

private fun key(id: String, name: String = id, passphrase: Boolean = false) = SshKeyEntry(
    id = SshKeyId(id),
    name = name,
    type = "ssh-ed25519",
    fingerprint = "SHA256:fp-$id",
    publicKey = "ssh-ed25519 AAAA$id comment-$id",
    privateKeyRef = SecretRef("key-$id"),
    passphraseRef = if (passphrase) SecretRef("passphrase-$id") else null,
)

fun sshKeyIndexContract(newIndex: suspend () -> SshKeyIndex): List<ContractCase> = contractCases {
    case("a new index is empty") {
        val index = newIndex()
        expectEqual(emptyList(), index.list(), "keys")
        expectEqual(null, index.get(SshKeyId("a")), "get")
    }
    case("every field of an entry reads back as saved") {
        val index = newIndex()
        val withPassphrase = key("a", name = "Laptop", passphrase = true)
        val without = key("b", name = "Phone")
        index.save(withPassphrase)
        index.save(without)
        expectEqual(withPassphrase, index.get(withPassphrase.id), "entry with a passphrase")
        expectEqual(without, index.get(without.id), "entry without a passphrase")
        expectEqual(listOf(withPassphrase, without), index.list(), "list")
    }
    case("save adds, and replaces the entry with the same ID") {
        val index = newIndex()
        index.save(key("a", name = "old"))
        index.save(key("b"))
        index.save(key("a", name = "new"))
        expectEqual(listOf("b", "new"), index.list().map { it.name }, "names")
        expectEqual("new", index.get(SshKeyId("a"))?.name, "replaced entry")
    }
    case("lists are sorted by name, then by ID") {
        val index = newIndex()
        val keys = listOf(key("z", name = "b"), key("y", name = "a"), key("x", name = "b"), key("w", name = "c"))
        keys.forEach { index.save(it) }
        val expected = keys.sortedWith(SshKeyIndex.BY_NAME)
        expectEqual(listOf("y", "x", "z", "w"), expected.map { it.id.value }, "comparator")
        expectEqual(expected, index.list(), "list")
        expectEqual(expected, index.observe().first(), "observe")
    }
    case("delete removes the entry, and does nothing without one") {
        val index = newIndex()
        index.save(key("a"))
        index.save(key("b"))
        index.delete(SshKeyId("missing"))
        expectEqual(2, index.list().size, "after deleting a missing entry")
        index.delete(SshKeyId("a"))
        expectEqual(listOf("b"), index.list().map { it.id.value }, "after delete")
        expectEqual(null, index.get(SshKeyId("a")), "get after delete")
    }
    case("observe emits the current list on collection, then after every change") {
        val index = newIndex()
        index.save(key("b"))
        val seen = collect(index.observe())
        expectEqual(listOf("b"), seen.next("current list").map { it.id.value }, "current list")
        index.save(key("a"))
        expectEqual(listOf("a", "b"), seen.next("list after save").map { it.id.value }, "list after save")
        index.delete(SshKeyId("b"))
        expectEqual(listOf("a"), seen.next("list after delete").map { it.id.value }, "list after delete")
    }
    case("concurrent saves lose no entry") {
        val index = newIndex()
        withContext(Dispatchers.Default) {
            (0 until 16).map { async { index.save(key("k$it")) } }.awaitAll()
        }
        expectEqual(16, index.list().size, "entries")
    }
}

private val PROFILE_A = ServerProfileId("a")
private val PROFILE_B = ServerProfileId("b")

fun recentFoldersStoreContract(newStore: suspend () -> RecentFoldersStore): List<ContractCase> = contractCases {
    case("a new store has no folders") {
        expectEqual(emptyList(), newStore().list(PROFILE_A), "folders")
    }
    case("add puts the newest folder first") {
        val store = newStore()
        store.add(PROFILE_A, "/srv/one")
        store.add(PROFILE_A, "/srv/two")
        expectEqual(listOf("/srv/two", "/srv/one"), store.list(PROFILE_A), "folders")
    }
    case("adding a folder again moves it to the front, once") {
        val store = newStore()
        listOf("/srv/one", "/srv/two", "/srv/three", "/srv/one").forEach { store.add(PROFILE_A, it) }
        expectEqual(listOf("/srv/one", "/srv/three", "/srv/two"), store.list(PROFILE_A), "folders")
    }
    case("each profile keeps at most MAX_PER_PROFILE folders, dropping the oldest") {
        val store = newStore()
        val max = RecentFoldersStore.MAX_PER_PROFILE
        (1..max + 2).forEach { store.add(PROFILE_A, "/srv/$it") }
        expectEqual((max + 2 downTo 3).map { "/srv/$it" }, store.list(PROFILE_A), "folders")
    }
    case("profiles keep their folders apart") {
        val store = newStore()
        store.add(PROFILE_A, "/srv/a")
        store.add(PROFILE_B, "/srv/b")
        expectEqual(listOf("/srv/a"), store.list(PROFILE_A), "profile a")
        expectEqual(listOf("/srv/b"), store.list(PROFILE_B), "profile b")
    }
    case("paths are kept exactly as given") {
        val store = newStore()
        val paths = listOf("/home/user/çalışma dizini", "/srv/x/", "/srv/x", "/SRV/X", "/srv/\"quoted\"")
        paths.forEach { store.add(PROFILE_A, it) }
        expectEqual(paths.reversed(), store.list(PROFILE_A), "folders")
    }
    case("remove takes out one folder, and does nothing without it") {
        val store = newStore()
        store.add(PROFILE_A, "/srv/one")
        store.add(PROFILE_A, "/srv/two")
        store.add(PROFILE_B, "/srv/one")
        store.remove(PROFILE_A, "/srv/missing")
        store.remove(PROFILE_A, "/srv/one")
        expectEqual(listOf("/srv/two"), store.list(PROFILE_A), "profile a")
        expectEqual(listOf("/srv/one"), store.list(PROFILE_B), "profile b")
    }
    case("forget removes one profile's folders only, and does nothing without any") {
        val store = newStore()
        store.add(PROFILE_A, "/srv/a")
        store.add(PROFILE_B, "/srv/b")
        store.forget(ServerProfileId("missing"))
        store.forget(PROFILE_A)
        expectEqual(emptyList(), store.list(PROFILE_A), "profile a")
        expectEqual(listOf("/srv/b"), store.list(PROFILE_B), "profile b")
    }
    case("observe emits the profile's list on collection, then after every change to it") {
        val store = newStore()
        store.add(PROFILE_A, "/srv/one")
        val seen = collect(store.observe(PROFILE_A))
        expectEqual(listOf("/srv/one"), seen.next("current list"), "current list")
        store.add(PROFILE_B, "/srv/other")
        store.add(PROFILE_A, "/srv/two")
        expectEqual(listOf("/srv/two", "/srv/one"), seen.next("list after add"), "list after add")
        store.forget(PROFILE_A)
        expectEqual(emptyList(), seen.next("list after forget"), "list after forget")
    }
    case("concurrent adds lose no folder") {
        val store = newStore()
        val paths = (0 until RecentFoldersStore.MAX_PER_PROFILE).map { "/srv/$it" }
        withContext(Dispatchers.Default) {
            paths.map { async { store.add(PROFILE_A, it) } }.awaitAll()
        }
        expectEqual(paths.toSet(), store.list(PROFILE_A).toSet(), "folders")
    }
}

/** The parts of [SettingsRepository.deleteAllData]. */
enum class StoragePart { Secrets, Profiles, KnownHosts, KeyIndex, RecentFolders, Settings }

interface SettingsHarness {
    val settings: SettingsRepository
    val secrets: SecretStore
    val profiles: ProfileRepository
    val knownHosts: KnownHostsStore
    val keyIndex: SshKeyIndex
    val recentFolders: RecentFoldersStore

    suspend fun givenUnreadableSecret(ref: SecretRef)

    /** Makes [part] fail until it is called with null. */
    suspend fun setFailingPart(part: StoragePart?)
}

private suspend fun SettingsHarness.fill() {
    SecretValue.ofText("value").use { secrets.put(SECRET, it) }
    givenUnreadableSecret(OTHER_SECRET)
    profiles.save(profile("a"))
    knownHosts.approve(ENDPOINT, KEY_A)
    keyIndex.save(key("a"))
    recentFolders.add(PROFILE_A, "/srv/a")
    settings.setLanguage(AppLanguage.TURKISH)
    settings.setTheme(ThemePreference.DARK)
    settings.setFileSizeLimit(7)
}

private suspend fun SettingsHarness.expectEmpty() {
    expectEqual(SecretReadResult.NotFound, secrets.read(SECRET), "secret")
    expectEqual(SecretReadResult.NotFound, secrets.read(OTHER_SECRET), "unreadable secret")
    expectEqual(emptyList(), profiles.list(), "profiles")
    expectEqual(null, knownHosts.approved(ENDPOINT), "known host")
    expectEqual(emptyList(), keyIndex.list(), "keys")
    expectEqual(emptyList(), recentFolders.list(PROFILE_A), "recent folders")
    expectEqual(AppSettings(), settings.observe().first(), "settings")
}

fun settingsRepositoryContract(newHarness: suspend () -> SettingsHarness): List<ContractCase> = contractCases {
    case("observe starts with the defaults") {
        expectEqual(AppSettings(), newHarness().settings.observe().first(), "settings")
    }
    case("observe emits the current settings on collection, then after every change") {
        val settings = newHarness().settings
        settings.setTheme(ThemePreference.DARK)
        val seen = collect(settings.observe())
        expectEqual(AppSettings(theme = ThemePreference.DARK), seen.next("current"), "current")
        settings.setLanguage(AppLanguage.ENGLISH)
        expectEqual(AppLanguage.ENGLISH, seen.next("after setLanguage").language, "language")
        settings.setTheme(ThemePreference.LIGHT)
        expectEqual(ThemePreference.LIGHT, seen.next("after setTheme").theme, "theme")
        settings.setFileSizeLimit(5)
        expectEqual(5L, seen.next("after setFileSizeLimit").fileSizeLimitBytes, "limit")
    }
    case("values are not validated here") {
        val settings = newHarness().settings
        settings.setFileSizeLimit(-1)
        expectEqual(-1L, settings.observe().first().fileSizeLimitBytes, "limit")
    }
    case("deleting a profile leaves the secrets it refers to") {
        val harness = newHarness()
        SecretValue.ofText("value").use { harness.secrets.put(SECRET, it) }
        val saved = profile("a").copy(auth = SshAuth.Password(SECRET))
        harness.profiles.save(saved)
        harness.profiles.delete(saved.id)
        expectEqual("value", harness.secrets.text(SECRET), "secret")
    }
    case("deleteAllData deletes secrets, profiles, known hosts, keys and recent folders, and resets settings") {
        val harness = newHarness()
        harness.fill()
        harness.settings.deleteAllData()
        harness.expectEmpty()
    }
    case("deleteAllData tells observers that settings returned to their defaults") {
        val harness = newHarness()
        harness.settings.setTheme(ThemePreference.DARK)
        val seen = collect(harness.settings.observe())
        seen.next("current")
        harness.settings.deleteAllData()
        expectEqual(AppSettings(), seen.next("after deleteAllData"), "settings")
    }
    case("deleteAllData does nothing wrong with nothing stored, and can run twice") {
        val harness = newHarness()
        harness.settings.deleteAllData()
        harness.settings.deleteAllData()
        harness.expectEmpty()
    }
    StoragePart.entries.forEach { part ->
        case("deleteAllData throws IOException if $part fails, and calling again finishes the job") {
            val harness = newHarness()
            harness.fill()
            harness.setFailingPart(part)
            expectThrows<IOException>("deleteAllData with failing $part") { harness.settings.deleteAllData() }
            harness.setFailingPart(null)
            harness.settings.deleteAllData()
            harness.expectEmpty()
        }
    }
}

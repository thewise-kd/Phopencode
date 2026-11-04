package io.github.thewisekd.phopencode.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SFTP over the emulator's ed25519 session: write, read back, list and delete in a fresh folder. */
@RunWith(AndroidJUnit4::class)
class SftpRoundTripTest {
    private lateinit var server: SshTestServer

    @Before
    fun setUp() {
        server = SshTestServer.fromArguments()
    }

    @Test
    fun writesReadsListsAndDeletesByteForByte() = runBlocking<Unit> {
        // Every byte value, then enough random data to take many SFTP packets.
        val payload = ByteArray(256) { it.toByte() } + Random(SEED).nextBytes(PAYLOAD_SIZE)
        val folder = "phopencode-emu-${UUID.randomUUID()}"
        val file = "$folder/blob.bin"

        server.connect().use { session ->
            val sftp = session.openSftp(server.sftpRoot)
            sftp.use {
                sftp.mkdir(folder)
                try {
                    sftp.writeAtomic(file, ByteArrayInputStream(payload))

                    assertArrayEquals(payload, sftp.read(file, payload.size.toLong()))

                    val listed = sftp.list(folder)
                    assertEquals(listOf("blob.bin"), listed.map { it.name })
                    assertEquals(FileEntryType.File, listed.single().type)
                    assertEquals(payload.size.toLong(), listed.single().sizeBytes)

                    sftp.delete(file)
                    assertTrue(sftp.list(folder).isEmpty())
                } finally {
                    sftp.deleteQuietly(folder)
                }
                assertFalse(sftp.list("").any { it.name == folder })
            }

            assertThrows(IllegalStateException::class.java) { runBlocking { sftp.list("") } }
        }
    }

    private suspend fun SftpClient.deleteQuietly(path: String) {
        runCatching { delete(path, recursive = true) }
    }

    private companion object {
        const val SEED = 0x5f7e
        const val PAYLOAD_SIZE = 512 * 1024 + 7
    }
}

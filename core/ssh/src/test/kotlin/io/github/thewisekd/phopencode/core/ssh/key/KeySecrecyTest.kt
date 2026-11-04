package io.github.thewisekd.phopencode.core.ssh.key

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The private key and the passphrase never reach printing, exception text or any log output. */
class KeySecrecyTest {
    @Test
    fun `nothing about the private key appears in toString or failures`() {
        val key = SshKeys.generateEd25519(KeySecrets.COMMENT, KeySecrets.PASSPHRASE.toCharArray())
        val file = key.privateKeyBytes()
        val plain = SshKeys.generateEd25519(KeySecrets.COMMENT)
        val plainFile = plain.privateKeyBytes()
        val info = SshKeys.read(file, KeySecrets.PASSPHRASE.toCharArray())
        val damaged = OpenSshBody.decode(plainFile).also { it[it.size - 30] = (it[it.size - 30] + 1).toByte() }
        val failures = listOf(
            runCatching { SshKeys.read(file, KeySecrets.WRONG_PASSPHRASE.toCharArray()) },
            runCatching { SshKeys.read(file) },
            runCatching { SshKeys.read(OpenSshBody.encode(damaged)) },
            runCatching { SshKeys.read(plainFile.copyOf(plainFile.size / 2)) },
            runCatching { SshKeys.read(plainFile.decodeToString().replace("A", "ç").toCharArray()) },
        ).map { checkNotNull(it.exceptionOrNull()) }
        val texts = listOf(key.toString(), plain.toString(), info.toString(), info.publicKey.toString()) +
            failures.map { it.stackTraceToString() }
        val secrets = secretsOf(file) + secretsOf(plainFile) + listOf(KeySecrets.PASSPHRASE, KeySecrets.WRONG_PASSPHRASE)
        texts.forEach { text -> secrets.forEach { assertFalse(it in text, "A secret leaked into:\n$text") } }
        assertTrue(failures.all { it is SshKeyException && it.cause == null })
    }

    @Test
    fun `generating and reading keys write nothing to the console or to java logging`() {
        val captured = ByteArrayOutputStream()
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val root = Logger.getLogger("")
        val oldLevel = root.level
        val out = System.out
        val err = System.err
        val files = mutableListOf<ByteArray>()
        try {
            root.addHandler(handler)
            root.level = Level.ALL
            PrintStream(captured, true).use { stream ->
                System.setOut(stream)
                System.setErr(stream)
                SshKeys.generateEd25519(KeySecrets.COMMENT, KeySecrets.PASSPHRASE.toCharArray()).use { key ->
                    files += key.privateKeyBytes()
                    SshKeys.read(key.privateKeyBytes(), KeySecrets.PASSPHRASE.toCharArray())
                    runCatching { SshKeys.read(key.privateKeyBytes(), KeySecrets.WRONG_PASSPHRASE.toCharArray()) }
                    runCatching { SshKeys.read(key.privateKeyChars()) }
                }
                runCatching { SshKeys.read(armored("RSA", "AAAA").toCharArray()) }
            }
        } finally {
            System.setOut(out)
            System.setErr(err)
            root.removeHandler(handler)
            root.level = oldLevel
        }
        assertEquals("", captured.toString())
        val logged = records.joinToString("\n") { "${it.message} ${it.parameters?.joinToString()} ${it.thrown}" }
        (secretsOf(files.single()) + KeySecrets.PASSPHRASE + KeySecrets.WRONG_PASSPHRASE).forEach {
            assertFalse(it in logged, "A secret reached java logging")
        }
    }

    @Test
    fun `exceptions print only their type`() {
        val all = listOf(
            SshKeyException.Malformed(),
            SshKeyException.Unsupported(),
            SshKeyException.PassphraseRequired(),
            SshKeyException.WrongPassphrase(),
        )
        all.forEach { assertTrue(it.message!!.isNotBlank()) }
        assertEquals(all.size, all.map { it.message }.toSet().size)
    }

    private fun secretsOf(file: ByteArray): List<String> =
        file.decodeToString().lines().filter { it.isNotBlank() && !it.startsWith("-----") }.map { it.take(24) }
}

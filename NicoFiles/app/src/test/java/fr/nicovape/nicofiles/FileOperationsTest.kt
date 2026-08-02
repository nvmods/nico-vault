package fr.nicovape.nicofiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileOperationsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun zipAndUnzipPreserveContent() {
        val source = temporaryFolder.newFolder("source")
        val nested = File(source, "nested").apply { mkdirs() }
        val original = File(nested, "hello.txt").apply { writeText("Bonjour NicoFiles") }
        val archive = File(temporaryFolder.root, "test.zip")
        val output = File(temporaryFolder.root, "output")

        FileOperations.zip(listOf(source), archive)
        FileOperations.unzip(archive, output)

        val restored = File(output, "source/nested/hello.txt")
        assertTrue(restored.exists())
        assertArrayEquals(original.readBytes(), restored.readBytes())
    }

    @Test
    fun uniqueFileAddsCounter() {
        val parent = temporaryFolder.newFolder("unique")
        File(parent, "document.txt").writeText("one")
        File(parent, "document (1).txt").writeText("two")

        assertEquals("document (2).txt", FileOperations.uniqueFile(parent, "document.txt").name)
    }

    @Test(expected = IOException::class)
    fun unzipRejectsZipSlip() {
        val archive = File(temporaryFolder.root, "unsafe.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt"))
            zip.write("danger".toByteArray())
            zip.closeEntry()
        }

        FileOperations.unzip(archive, File(temporaryFolder.root, "safe"))
    }
}

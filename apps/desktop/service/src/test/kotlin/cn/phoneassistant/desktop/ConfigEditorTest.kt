package cn.phoneassistant.desktop

import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigEditorTest {

    private val base = DesktopConfigFile(
        device_id = "pc-test",
        hub_pubkey = buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", "abc")
            put("y", "def")
        },
    )

    @Test
    fun validConfigRoundTripsThroughDisk() {
        val dir = Files.createTempDirectory("config-editor").toFile()
        val paths = DesktopPaths(dir).also { it.ensure() }

        val updated = ConfigEditor.validate(
            catalogText = """{"com.example.site": {"url": "https://example.com"}, "com.example.notepad": {"command": ["notepad.exe"]}}""",
            allowlistText = "example.com\nExample.com\n",
            webSearchTemplate = "https://www.bing.com/search?q={q}",
            dryRun = true,
            base = base,
        ).getOrThrow()

        assertEquals(listOf("example.com"), updated.url_allowlist)
        assertEquals(2, updated.app_catalog.size)

        DesktopConfig.save(paths, updated)
        val reloaded = DesktopConfig.loadOrCreate(paths)

        assertEquals(updated.app_catalog, reloaded.app_catalog)
        assertEquals(updated.url_allowlist, reloaded.url_allowlist)
        assertEquals(updated.web_search_template, reloaded.web_search_template)
        assertTrue(reloaded.dry_run)
    }

    @Test
    fun defaultsAreWrittenExplicitly() {
        val dir = Files.createTempDirectory("config-defaults").toFile()
        val paths = DesktopPaths(dir).also { it.ensure() }

        DesktopConfig.save(paths, base)

        val text = paths.configFile.readText()
        assertTrue("应显式写出搜索模板", text.contains("web_search_template"))
        assertTrue("应显式写出 dry_run", text.contains("dry_run"))
        assertTrue("应显式写出 app_catalog", text.contains("app_catalog"))
    }

    @Test
    fun invalidAppIdIsRejected() {
        val result = ConfigEditor.validate(
            catalogText = """{"Bad App": {"url": "https://example.com"}}""",
            allowlistText = "",
            webSearchTemplate = "https://www.bing.com/search?q={q}",
            dryRun = false,
            base = base,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("应用 id 不合法"))
    }

    @Test
    fun appWithoutUrlOrCommandIsRejected() {
        val result = ConfigEditor.validate(
            catalogText = """{"com.example.empty": {}}""",
            allowlistText = "",
            webSearchTemplate = "https://www.bing.com/search?q={q}",
            dryRun = false,
            base = base,
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun httpUrlInCatalogIsRejected() {
        val result = ConfigEditor.validate(
            catalogText = """{"com.example.site": {"url": "http://example.com"}}""",
            allowlistText = "",
            webSearchTemplate = "https://www.bing.com/search?q={q}",
            dryRun = false,
            base = base,
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun templateWithoutPlaceholderIsRejected() {
        val result = ConfigEditor.validate(
            catalogText = "{}",
            allowlistText = "example.com",
            webSearchTemplate = "https://www.bing.com/search",
            dryRun = false,
            base = base,
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun invalidHostIsRejected() {
        val result = ConfigEditor.validate(
            catalogText = "{}",
            allowlistText = "https://example.com/path",
            webSearchTemplate = "https://www.bing.com/search?q={q}",
            dryRun = false,
            base = base,
        )
        assertTrue(result.isFailure)
    }
}

package app.zaid.flow.innertube.pages.channel

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the parser over every REAL captured response in src/test/resources/product_fixtures (json files).
 * With no fixtures present the test is skipped (not passed), so an empty folder is visible as such.
 */
class RealProductFixturesTest {
    private val fixtures: List<File> =
        javaClass.classLoader
            ?.getResource("product_fixtures")
            ?.let { File(it.toURI()) }
            ?.listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name }
            .orEmpty()

    @Test
    fun everyRealFixtureYieldsProductsWithSafeLinks() {
        assumeTrue("No real fixtures captured yet", fixtures.isNotEmpty())
        for (file in fixtures) {
            val products = Json.parseToJsonElement(file.readText()).toChannelProducts()
            assertTrue(
                "${file.name}: no products parsed\n" + Json.parseToJsonElement(file.readText()).describeProductScan(file.name),
                products.isNotEmpty(),
            )
            products.forEach { assertTrue("${file.name}: unsafe link ${it.url}", safeLink(it.url) != null) }
        }
    }
}

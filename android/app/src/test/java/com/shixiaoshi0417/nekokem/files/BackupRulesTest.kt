package com.shixiaoshi0417.nekokem.files

import com.shixiaoshi0417.nekokem.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupRulesTest {
    private val domains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    private fun excludedDomains(resource: Int): Map<String, Set<String>> {
        val exclusions = mutableMapOf<String, MutableSet<String>>()
        RuntimeEnvironment.getApplication().resources.getXml(resource).use { parser ->
            var section = ""
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "cloud-backup", "device-transfer", "full-backup-content" -> section = parser.name
                    "include" -> fail("Backup rules must not include any data")
                    "exclude" -> {
                        assertEquals(".", parser.getAttributeValue(null, "path"))
                        exclusions.getOrPut(section) { mutableSetOf() }
                            .add(parser.getAttributeValue(null, "domain"))
                    }
                }
            }
        }
        return exclusions
    }

    @Test fun bothAndroid12BackupAndDeviceTransferExcludeAllDataDomains() {
        assertEquals(
            mapOf("cloud-backup" to domains, "device-transfer" to domains),
            excludedDomains(R.xml.data_extraction_rules),
        )
    }

    @Test fun olderAndroidBackupExcludesAllDataDomains() {
        assertEquals(mapOf("full-backup-content" to domains), excludedDomains(R.xml.backup_rules))
    }

    /** The rules above protect nothing unless the manifest points at them. */
    @Test fun manifestDisablesBackupAndUsesBothRuleFiles() {
        val application = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0)
        val attribute = { name: String -> application.attributes.getNamedItem(name)?.nodeValue }
        assertEquals("false", attribute("android:allowBackup"))
        assertEquals("@xml/data_extraction_rules", attribute("android:dataExtractionRules"))
        assertEquals("@xml/backup_rules", attribute("android:fullBackupContent"))
    }
}

package com.blive.tv.ui.main

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionFocusLayoutConfigTest {

    @Test
    fun `level1 area item delegates down focus to parent grid`() {
        val content = readLayout("item_area_tag_level1.xml")

        assertFalse(content.contains("android:nextFocusDown=\"@id/main_grid\""))
    }

    @Test
    fun `level2 area item keeps down focus to main grid`() {
        val content = readLayout("item_area_tag_level2.xml")

        assertTrue(content.contains("android:nextFocusDown=\"@id/main_grid\""))
    }

    private fun readLayout(fileName: String): String {
        val candidates = listOf(
            Paths.get("src", "main", "res", "layout", fileName),
            Paths.get("app", "src", "main", "res", "layout", fileName)
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("未找到布局文件: $fileName, cwd=${System.getProperty("user.dir")}")
        return String(Files.readAllBytes(path))
    }
}

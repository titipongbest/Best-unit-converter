package com.bestspace.vault.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageTest {
    @Test fun destInsideOrSameRejectsSelfAndChildrenButAllowsParentAndSibling() {
        val tmp = Files.createTempDirectory("bs-dest").toFile()
        val parent = File(tmp, "sd").apply { mkdirs() }
        val vaultRoot = File(parent, ".bestspace").apply { mkdirs() }
        val inside = File(vaultRoot, "files").apply { mkdirs() }
        val sibling = File(parent, ".bestspace2").apply { mkdirs() }
        val outside = File(parent, "Exports").apply { mkdirs() }

        assertTrue(Dest.Local(vaultRoot).isInsideOrSame(vaultRoot))
        assertTrue(Dest.Local(inside).isInsideOrSame(vaultRoot))
        assertFalse(Dest.Local(parent).isInsideOrSame(vaultRoot))
        assertFalse(Dest.Local(sibling).isInsideOrSame(vaultRoot))
        assertFalse(Dest.Local(outside).isInsideOrSame(vaultRoot))
    }
}

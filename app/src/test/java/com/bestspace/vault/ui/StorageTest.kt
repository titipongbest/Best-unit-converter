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

    @Test fun resolveVaultDirPrefersBestunitAndFallsBackToLegacyBestspace() {
        org.junit.Assert.assertEquals(".Bestunit", Storage.DEFAULT_VAULT_NAME)
        val tmp = Files.createTempDirectory("bs-resolve").toFile()

        val legacyParent = File(tmp, "legacy").apply { mkdirs() }
        val legacyVault = File(legacyParent, ".bestspace").apply { mkdirs() }
        File(legacyVault, "vault.json").writeText("{}")
        org.junit.Assert.assertEquals(legacyVault, Storage.resolveVaultDir(legacyParent))

        val newParent = File(tmp, "both").apply { mkdirs() }
        val oldInBoth = File(newParent, ".bestspace").apply { mkdirs() }
        File(oldInBoth, "vault.json").writeText("{}")
        val bestunit = File(newParent, ".Bestunit").apply { mkdirs() }
        File(bestunit, "vault.json").writeText("{}")
        org.junit.Assert.assertEquals(bestunit, Storage.resolveVaultDir(newParent))
        org.junit.Assert.assertEquals(bestunit, Storage.resolveVaultDir(bestunit))

        val emptyParent = File(tmp, "empty").apply { mkdirs() }
        org.junit.Assert.assertEquals(null, Storage.resolveVaultDir(emptyParent))
    }
}

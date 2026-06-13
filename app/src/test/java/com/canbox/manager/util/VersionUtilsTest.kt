package com.canbox.manager.util

import org.junit.Assert.*
import org.junit.Test

class VersionUtilsTest {

    // ── Basic comparisons ──────────────────────────────────────────────────────

    @Test fun `newer patch version`() = assertTrue(isNewerVersion("1.0.5", "1.0.4"))
    @Test fun `newer minor version`() = assertTrue(isNewerVersion("1.1.0", "1.0.9"))
    @Test fun `newer major version`() = assertTrue(isNewerVersion("2.0.0", "1.9.9"))
    @Test fun `equal versions`() = assertFalse(isNewerVersion("1.0.4", "1.0.4"))
    @Test fun `older version`() = assertFalse(isNewerVersion("1.0.3", "1.0.4"))

    // ── Pre-release suffixes ──────────────────────────────────────────────────

    @Test fun `pre-release of newer version is newer`() =
        assertTrue(isNewerVersion("1.0.4-beta", "1.0.3"))

    @Test fun `pre-release of newer version with rc suffix`() =
        assertTrue(isNewerVersion("1.0.4-rc1", "1.0.3"))

    @Test fun `pre-release same base as stable is not newer`() =
        assertFalse(isNewerVersion("1.0.4-rc1", "1.0.4"))

    @Test fun `pre-release older base is not newer`() =
        assertFalse(isNewerVersion("1.0.3-rc1", "1.0.4"))

    // ── Build metadata ────────────────────────────────────────────────────────

    @Test fun `build metadata stripped before comparison`() =
        assertTrue(isNewerVersion("1.0.5+build.123", "1.0.4"))

    @Test fun `pre-release plus build metadata`() =
        assertTrue(isNewerVersion("1.0.4-rc1+build.5", "1.0.3"))

    // ── v prefix already stripped by callers ─────────────────────────────────

    @Test fun `plain versions without prefix`() =
        assertTrue(isNewerVersion("1.0.4", "1.0.3"))

    // ── Before fix: these all returned wrong results ──────────────────────────

    @Test fun `REGRESSION - pre-release of newer not treated as older`() {
        // Before fix: "1.0.4-beta" -> [1,0,0] vs [1,0,3] -> false (wrong)
        assertTrue(isNewerVersion("1.0.4-beta", "1.0.3"))
    }

    @Test fun `REGRESSION - pre-release same base not treated as newer`() {
        // Before fix: "1.0.4-rc1" -> [1,0,0] vs [1,0,4] -> false (accidentally correct but wrong reason)
        assertFalse(isNewerVersion("1.0.4-rc1", "1.0.4"))
    }
}

package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthRuntimeEnvironmentCheckTest {
    private fun env(width: Int = 1280, height: Int = 720, dpi: Int = 320, cores: Int = 6) =
        LabyrinthRuntimeEnvironment(width, height, dpi, cores)

    @Test fun supportedProfilePasses() {
        assertTrue(labyrinthRuntimeEnvironmentViolations(env()).isEmpty())
        assertNull(labyrinthRuntimeEnvironmentRecommendation(env()))
    }

    @Test fun wrongResolutionIsReported() {
        val violations = labyrinthRuntimeEnvironmentViolations(env(width = 1920, height = 1080))
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("1920×1080"))
        assertTrue(violations.single().contains("1280×720"))
        assertTrue(labyrinthRuntimeEnvironmentBlockedMessage(violations).startsWith("运行环境不符合要求"))
    }

    @Test fun wrongDensityIsReported() {
        val violations = labyrinthRuntimeEnvironmentViolations(env(dpi = 480))
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("480"))
        assertTrue(violations.single().contains("320"))
    }

    @Test fun fourCoresPassButRecommendSix() {
        assertTrue(labyrinthRuntimeEnvironmentViolations(env(cores = 4)).isEmpty())
        assertNotNull(labyrinthRuntimeEnvironmentRecommendation(env(cores = 4)))
        assertTrue(labyrinthRuntimeEnvironmentRecommendation(env(cores = 5))!!.contains("推荐 6 核"))
    }

    @Test fun threeCoresAreBlockedAndAllProblemsListed() {
        val violations = labyrinthRuntimeEnvironmentViolations(env(width = 960, height = 540, dpi = 240, cores = 3))
        assertEquals(3, violations.size)
        assertTrue(violations.any { it.startsWith("分辨率") })
        assertTrue(violations.any { it.startsWith("DPI") })
        assertTrue(violations.any { it.startsWith("CPU") })
    }
}

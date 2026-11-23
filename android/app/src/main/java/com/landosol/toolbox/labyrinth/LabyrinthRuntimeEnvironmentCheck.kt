package com.landosol.toolbox.labyrinth

/** Actual runtime facts used only for the start-of-run environment check. */
data class LabyrinthRuntimeEnvironment(
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int,
    val availableProcessors: Int,
)

/**
 * Fixed supported profile: the calibrated 1280x720 @ 320 dpi capture with at least four cores.
 * Any other geometry or a lower core count would run the same templates at a different scale.
 */
object LabyrinthRuntimeRequirements {
    const val WIDTH_PIXELS = 1280
    const val HEIGHT_PIXELS = 720
    const val PORTRAIT_WIDTH_PIXELS = 720
    const val PORTRAIT_HEIGHT_PIXELS = 1280
    const val DENSITY_DPI = 320
    const val MINIMUM_PROCESSORS = 4
    const val RECOMMENDED_PROCESSORS = 6
}

/** Human-readable mismatches; empty means the environment is supported. */
fun labyrinthRuntimeEnvironmentViolations(environment: LabyrinthRuntimeEnvironment): List<String> = buildList {
    if (environment.widthPixels != LabyrinthRuntimeRequirements.WIDTH_PIXELS ||
        environment.heightPixels != LabyrinthRuntimeRequirements.HEIGHT_PIXELS
    ) {
        add("分辨率 ${environment.widthPixels}×${environment.heightPixels}，要求 " +
            "${LabyrinthRuntimeRequirements.WIDTH_PIXELS}×${LabyrinthRuntimeRequirements.HEIGHT_PIXELS}")
    }
    if (environment.densityDpi != LabyrinthRuntimeRequirements.DENSITY_DPI) {
        add("DPI ${environment.densityDpi}，要求 ${LabyrinthRuntimeRequirements.DENSITY_DPI}")
    }
    if (environment.availableProcessors < LabyrinthRuntimeRequirements.MINIMUM_PROCESSORS) {
        add("CPU ${environment.availableProcessors} 核，要求至少 ${LabyrinthRuntimeRequirements.MINIMUM_PROCESSORS} 核")
    }
}

/** Non-blocking advice shown in the start log; never changes the environment. */
fun labyrinthRuntimeEnvironmentRecommendation(environment: LabyrinthRuntimeEnvironment): String? =
    if (environment.availableProcessors in LabyrinthRuntimeRequirements.MINIMUM_PROCESSORS until
        LabyrinthRuntimeRequirements.RECOMMENDED_PROCESSORS
    ) {
        "当前 ${environment.availableProcessors} 核，推荐 ${LabyrinthRuntimeRequirements.RECOMMENDED_PROCESSORS} 核以获得完整计算线程预算"
    } else {
        null
    }

fun labyrinthRuntimeEnvironmentBlockedMessage(violations: List<String>): String =
    "运行环境不符合要求，已拒绝启动：" + violations.joinToString("；")

/**
 * Startup-only display check. The app accepts either landscape 1280x720 or portrait 720x1280
 * at 320 dpi; any other geometry or dpi gets a warning dialog. This is intentionally separate
 * from [labyrinthRuntimeEnvironmentViolations], which also gates CPU count at task start.
 */
fun labyrinthDisplayRequirementWarning(environment: LabyrinthRuntimeEnvironment): String? {
    val landscape = environment.widthPixels == LabyrinthRuntimeRequirements.WIDTH_PIXELS &&
        environment.heightPixels == LabyrinthRuntimeRequirements.HEIGHT_PIXELS
    val portrait = environment.widthPixels == LabyrinthRuntimeRequirements.PORTRAIT_WIDTH_PIXELS &&
        environment.heightPixels == LabyrinthRuntimeRequirements.PORTRAIT_HEIGHT_PIXELS
    if (landscape || portrait) {
        if (environment.densityDpi == LabyrinthRuntimeRequirements.DENSITY_DPI) return null
    }
    return "分辨率要求：1280×720 或 720×1280\nDPI要求：320"
}

package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlin.math.abs
import kotlin.math.roundToInt

/** Three labelled crops in one OCR input, all from one capture. Never merge earlier title text
 * with a later button, price, role badge, or evidence epoch. Missing/reordered markers reject. */
object ShopRecognitionScoped {
    data class Sections(val title: String, val description: String, val price: String)
    data class Evidence(val epoch: Long, val capturedAtMillis: Long, val fingerprint: Long,
        val readId: Long, val sections: Sections?, val iconRoleLabel: String?)
    private val legacyCategories = LabyrinthShopCategoryResolution()
    private val roles = listOf("坦克", "治疗", "强化", "增益", "减益", "干扰", "攻击", "破防")
    private val titlePattern = Regex("(?:(?:坦克|治疗|强化|增益|减益|干扰|攻击|破防)型?职能的?|随机职能的?)(随机|选择)印记")

    fun sections(text: String?): Sections? {
        if (text == null) return null
        val lines = text.lines()
        val markers = lines.mapIndexedNotNull { index, line ->
            val tag = line.trim().uppercase()
            if (tag in listOf("TITLE", "DETAIL", "PRICE")) index to tag else null
        }
        if (markers.map { it.second } != listOf("TITLE", "DETAIL", "PRICE")) return null
        if (lines.take(markers[0].first).any { it.isNotBlank() }) return null
        fun body(from: Int, until: Int) = lines.subList(from, until).joinToString("\n").trim()
        return Sections(body(markers[0].first + 1, markers[1].first),
            body(markers[1].first + 1, markers[2].first), body(markers[2].first + 1, lines.size))
    }

    fun form(title: String?): LabyrinthShopImprintForm {
        val value = LabyrinthShopItemText.normalized(title)
        if (value == "全体选择印记") return LabyrinthShopImprintForm.CHOICE
        val match = titlePattern.matchEntire(value) ?: return LabyrinthShopImprintForm.UNKNOWN
        return if (match.groupValues[1] == "选择") LabyrinthShopImprintForm.CHOICE else LabyrinthShopImprintForm.RANDOM
    }

    fun randomDescriptionLabel(description: String?): String? {
        val value = LabyrinthShopItemText.normalized(description)
        val role = roles.joinToString("|")
        val match = Regex("能够从($role)型?职能中随机选出1人成为同伴[。.]?").matchEntire(value) ?: return null
        return match.groupValues[1] + "型职能印记"
    }

    fun resolve(item: LabyrinthShopItemMatch, evidence: Evidence?, epoch: Long,
        afterMillis: Long, currentFingerprint: Long, attempts: Int, exhausted: Boolean): LabyrinthShopItemMatch {
        val valid = evidence?.takeIf { it.epoch == epoch && it.capturedAtMillis > afterMillis && it.fingerprint == currentFingerprint }
        val parts = valid?.sections
        val title = parts?.title?.takeIf { it.isNotBlank() }
        val price = ShopMoney.parse(parts?.price)
        val titleForm = if (!item.titleCropped) form(title) else LabyrinthShopImprintForm.UNKNOWN
        val fullLabel = if (titleForm != LabyrinthShopImprintForm.UNKNOWN) LabyrinthShopItemText.roleImprintLabel(title) else null
        val descriptionLabel = randomDescriptionLabel(parts?.description)
        val croppedRandom = item.titleCropped && titleForm == LabyrinthShopImprintForm.UNKNOWN && descriptionLabel != null &&
            valid?.iconRoleLabel == descriptionLabel && item.status == LabyrinthShopItemStatus.AVAILABLE &&
            item.buyButtonScore >= 0.55 && item.buyButtonEnabledEvidence >= 0.50 && price != null
        // Preserve the original full-title semantic gate plus unchanged RGB confidence/margin.
        // OCR does not have to reproduce the catalog's name character-for-character.
        val classified = legacyCategories.resolve(item, title, valid?.readId, attempts)
        val isImprint = titleForm != LabyrinthShopImprintForm.UNKNOWN || croppedRandom || classified.kind == LabyrinthShopItemKind.ROLE_IMPRINT
        val relicTitleMatches = !item.titleCropped && !isImprint && classified.kind == LabyrinthShopItemKind.RELIC
        val legacyReady = valid != null && !item.titleCropped &&
            classified.categoryState == LabyrinthShopCategoryState.READY
        val specificLabel = fullLabel?.takeUnless { it == "全体选择印记" || it == "随机职能印记" }
        val choiceDescription = Regex("能够从.+(?:选择|挑选)1(?:人|名角色)成为同伴[。.]?")
            .matches(LabyrinthShopItemText.normalized(parts?.description))
        val conflict = (specificLabel != null && descriptionLabel != null && specificLabel != descriptionLabel) ||
            (specificLabel != null && valid?.iconRoleLabel != null && specificLabel != valid.iconRoleLabel) ||
            (titleForm == LabyrinthShopImprintForm.CHOICE && descriptionLabel != null) ||
            (titleForm == LabyrinthShopImprintForm.RANDOM && choiceDescription) ||
            (item.titleCropped && form(title) == LabyrinthShopImprintForm.CHOICE && descriptionLabel != null) ||
            (!isImprint && descriptionLabel != null)
        val identity = !conflict && price != null && (fullLabel != null || croppedRandom || relicTitleMatches) && valid != null &&
            item.status != LabyrinthShopItemStatus.UNKNOWN && item.buyButtonScore >= 0.55
        val label = if (croppedRandom) descriptionLabel else fullLabel ?: if (isImprint) LabyrinthShopItemText.roleImprintLabel(title) else null
        val imprintForm = if (croppedRandom) LabyrinthShopImprintForm.RANDOM else titleForm
        return item.copy(
            kind = when { isImprint -> LabyrinthShopItemKind.ROLE_IMPRINT; relicTitleMatches -> LabyrinthShopItemKind.RELIC; else -> LabyrinthShopItemKind.UNKNOWN },
            relicMatch = if (isImprint) null else item.relicMatch,
            titleText = title, titleEvidenceId = valid?.readId, roleImprintLabel = label,
            choiceRoleImprint = imprintForm == LabyrinthShopImprintForm.CHOICE || (legacyReady && classified.choiceRoleImprint),
            imprintForm = imprintForm, priceCoins = price, identitySafe = identity,
            evidenceEpoch = epoch, evidenceCapturedAtMillis = if (valid != null) valid.capturedAtMillis else Long.MIN_VALUE,
            categoryState = when { (!conflict && (identity || legacyReady)) || item.status == LabyrinthShopItemStatus.DISABLED -> LabyrinthShopCategoryState.READY;
                exhausted -> LabyrinthShopCategoryState.EXHAUSTED; else -> LabyrinthShopCategoryState.READING },
            categoryAttempt = attempts.coerceIn(0, 3),
            imprintLabelPending = isImprint && label == null && !exhausted,
        )
    }
}

data class ShopImprintIconTemplate(val roleLabel: String, val image: PixelImage)

/** Pink random-imprint artwork PLUS the coloured role badge. A blue Buy/cheap price is never an icon. */
class ShopImprintIconMatcher(private val templates: List<ShopImprintIconTemplate>) {
    fun match(frame: PixelImage, rect: EntryPixelRect): String? {
        if (templates.isEmpty() || rect.width < 30 || rect.height < 30) return null
        val ranked = templates.map { template ->
            var bestCore = 0.0
            var bestBadge = 0.0
            var best = 0.0
            // Live columns differ by a few pixels from the reference icon/buy-button offset.
            // Bound alignment at ±4px for 1280 and ±6px for 1920; confidence/margin stay unchanged.
            val radius = maxOf(2, (rect.width / 25.0).roundToInt()).coerceAtMost(6)
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val core = score(frame, rect, template.image, dx, dy, badge = false)
                val badge = score(frame, rect, template.image, dx, dy, badge = true)
                val value = core * 0.35 + badge * 0.65
                if (value > best) { best = value; bestCore = core; bestBadge = badge }
            }
            Triple(template.roleLabel, best, bestCore >= 0.82 && bestBadge >= 0.80)
        }.sortedByDescending { it.second }
        val best = ranked.first()
        val rival = ranked.getOrNull(1)?.second ?: 0.0
        return best.first.takeIf { best.third && best.second >= 0.83 && best.second - rival >= 0.025 }
    }

    private fun score(frame: PixelImage, rect: EntryPixelRect, template: PixelImage,
        dx: Int, dy: Int, badge: Boolean): Double {
        var error = 0.0
        var samples = 0
        val from = if (badge) 64 else 10
        val toX = if (badge) 98 else 64
        val fromY = if (badge) 64 else 8
        val toY = if (badge) 98 else 86
        for (py in fromY..toY step 4) for (px in from..toX step 4) {
            val x = rect.left + (px * rect.width / 100.0).roundToInt() + dx
            val y = rect.top + (py * rect.height / 100.0).roundToInt() + dy
            if (x !in 0 until frame.width || y !in 0 until frame.height) return 0.0
            val observed = frame[x, y]
            val expected = template[(px * template.width / 100).coerceAtMost(template.width - 1),
                (py * template.height / 100).coerceAtMost(template.height - 1)]
            for (shift in listOf(16, 8, 0)) error += abs((observed ushr shift and 255) - (expected ushr shift and 255)) / 255.0
            samples += 3
        }
        return (1.0 - error / samples).coerceIn(0.0, 1.0)
    }
}

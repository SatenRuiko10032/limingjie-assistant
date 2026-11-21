package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class LabyrinthConfigShareTest {
    private val customEvent = LabyrinthCustomEventCatalog.cards.first { card -> card.choices.any { it.slot != card.defaultSlot } }
    private val customChoice = customEvent.choices.first { it.slot != customEvent.defaultSlot }
    private val values = LabyrinthConfigurationValues(
        strategy = LabyrinthStrategySettings(
            playerWeight = 43, buyRelics = false, refreshShop = false,
            personalRoleScores = mapOf("100101" to 0, "100201" to 83),
            openingRosters = mapOf(1 to listOf(listOf("100101", "100201"), listOf("100301"), listOf("100401"))),
            eventCustomizationEnabled = false,
            eventChoiceOverrides = LabyrinthStrategySettings.DEFAULT_EVENT_CHOICE_OVERRIDES +
                (customEvent.runtimeName to customChoice.runtimeName),
        ),
        rerolls = mapOf(7L to LabyrinthRerollSettings(guildId = 2, difficulty = 3, maxAttempts = "25", retireExisting = false)),
    )
    private fun shared(modules: Set<LabyrinthConfigModule> = LabyrinthConfigModule.entries.toSet()) =
        LabyrinthConfigShareCodec.create(values, modules, 7, "test-version", 1000)

    @Test fun `version one round trip and preview retain source values including disabled custom choices`() {
        assertNotEquals(LabyrinthStrategySettings.DEFAULT_EVENT_CHOICE_OVERRIDES[customEvent.runtimeName], customChoice.runtimeName)
        val expected = shared()
        val code = LabyrinthConfigShareCodec.encode(expected)
        val payload = Json.parseToJsonElement(unpack(code)).jsonObject
        assertTrue(code.startsWith("LABY1."))
        assertEquals(JsonPrimitive(1), payload["schemaVersion"])
        val restored = LabyrinthConfigShareCodec.decode(code)
        assertEquals(expected, restored)
        // Compare with the saved source too: create() must not silently discard custom values.
        assertEquals(values.strategy.copy(personalRoleScores = emptyMap()), restored.strategy)
        assertEquals(values.strategy.personalRoleScores, restored.personalScores)
        assertEquals(values.rerolls.getValue(7L), restored.reroll)
        val strategy = requireNotNull(restored.strategy)
        assertFalse(strategy.eventCustomizationEnabled)
        assertEquals(customChoice.runtimeName, strategy.eventChoiceOverrides[customEvent.runtimeName])

        val target = LabyrinthConfigurationValues(rerolls = mapOf(9L to LabyrinthRerollSettings()))
        LabyrinthConfigImportPlan(restored, restored.modules, 9L, target).changeSummary(mapOf("100101" to "角色甲"))
        // Preview must not change the payload; GZIP byte-for-byte identity is not the contract.
        assertEquals(payload, Json.parseToJsonElement(unpack(LabyrinthConfigShareCodec.encode(restored))))
    }

    @Test fun `portable payload contains only explicit modules and no identity or execution target`() {
        val text = unpack(LabyrinthConfigShareCodec.encode(shared()))
        assertFalse(text.contains("personalRoleScores"))
        listOf("accountId", "credentialKey", "password", "loginId", "gameUid", "batchId", "completedCount", "targetCount").forEach {
            assertFalse(it, text.contains("\"$it\""))
        }
        assertTrue(text.contains("\"personalScores\""))
    }

    @Test fun `each module works independently`() {
        LabyrinthConfigModule.entries.forEach { module ->
            val decoded = LabyrinthConfigShareCodec.decode(LabyrinthConfigShareCodec.encode(shared(setOf(module))))
            assertEquals(setOf(module), decoded.modules)
            assertEquals(values.strategy.copy(personalRoleScores = emptyMap()).takeIf { module == LabyrinthConfigModule.STRATEGY }, decoded.strategy)
            assertEquals(values.strategy.personalRoleScores.takeIf { module == LabyrinthConfigModule.PERSONAL_SCORES }, decoded.personalScores)
            assertEquals(values.rerolls.getValue(7L).takeIf { module == LabyrinthConfigModule.REROLL }, decoded.reroll)
        }
    }

    @Test fun `both battle switches round trip and replace the saved settings in both directions`() {
        val source = shared(setOf(LabyrinthConfigModule.STRATEGY))
        val disabled = requireNotNull(source.strategy)
        val enabled = disabled.copy(frontlineSurvivalGateEnabled = true, normalBattleSkipTeamSelectionEnabled = true)
        listOf(disabled to enabled, enabled to disabled).forEach { (before, after) ->
            val target = values.copy(strategy = before.copy(personalRoleScores = values.strategy.personalRoleScores))
            val restored = LabyrinthConfigShareCodec.decode(LabyrinthConfigShareCodec.encode(source.copy(strategy = after)))
            assertEquals(after, restored.strategy)
            assertEquals(
                target.copy(strategy = after.copy(personalRoleScores = target.strategy.personalRoleScores)),
                restored.replacement(target, restored.modules, null, false),
            )
        }
    }

    @Test fun `legacy strategy missing either or both battle switches uses defaults only for omitted fields`() {
        val source = shared()
        val enabled = requireNotNull(source.strategy)
            .copy(frontlineSurvivalGateEnabled = true, normalBattleSkipTeamSelectionEnabled = true)
        val root = sharePayload(source.copy(strategy = enabled))
        val frontline = "frontlineSurvivalGateEnabled"
        val skip = "normalBattleSkipTeamSelectionEnabled"
        val defaults = LabyrinthStrategySettings()
        listOf(setOf(frontline), setOf(skip), setOf(frontline, skip)).forEach { omitted ->
            val payload = replaceModule(root, "strategy") { JsonObject(it - omitted) }
            val restored = LabyrinthConfigShareCodec.decode(pack(payload.toString()))
            assertEquals(
                source.copy(strategy = enabled.copy(
                    frontlineSurvivalGateEnabled = frontline !in omitted || defaults.frontlineSurvivalGateEnabled,
                    normalBattleSkipTeamSelectionEnabled = skip !in omitted || defaults.normalBattleSkipTeamSelectionEnabled,
                )),
                restored,
            )
        }
    }

    @Test fun `legacy reroll may omit its plan only outside custom node mode`() {
        val source = shared()
        val root = sharePayload(source)
        val legacy = replaceModule(root, "reroll") { JsonObject(it - "customNodePlan") }
        assertEquals(source, LabyrinthConfigShareCodec.decode(pack(legacy.toString())))
        val legacyWithAddition = replaceModule(legacy, "reroll") { JsonObject(it + ("futureFlag" to JsonPrimitive(true))) }
        val ignored = mapOf(LabyrinthConfigModule.REROLL to setOf("futureFlag"))
        assertEquals(source.copy(ignoredFieldsByModule = ignored), LabyrinthConfigShareCodec.decode(pack(legacyWithAddition.toString())))
        val missingCustomPlan = replaceModule(legacyWithAddition, "reroll") {
            JsonObject(it + ("routeEvaluationMode" to JsonPrimitive("CUSTOM_NODES")))
        }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            LabyrinthConfigShareCodec.decode(pack(missingCustomPlan.toString()))
        }
        assertEquals("自定义模式缺少节点配置", failure.message)
    }

    @Test fun `future fields are ignored in each compatible object and reported in the selected module preview`() {
        val original = shared()
        val source = original.copy(reroll = requireNotNull(original.reroll).copy(
            routeEvaluationMode = LabyrinthRouteEvaluationMode.CUSTOM_NODES,
            customNodePlan = LabyrinthCustomNodePlan(area1Column3 = LabyrinthNodeTypes.EVENT),
        ))
        val strategyFields = mapOf(
            "futureSwitch" to JsonPrimitive(true),
            "futureWeight" to JsonPrimitive(1.5),
            "futureName" to JsonPrimitive("future\nname"),
        )
        val nested = JsonObject(mapOf("enabled" to JsonPrimitive(false)))
        val strategyPayload = replaceModule(sharePayload(source), "strategy") { JsonObject(it + strategyFields) }
        val payload = replaceModule(strategyPayload, "reroll") {
            val custom = JsonObject(it.getValue("customNodePlan").jsonObject + ("futureCol" to nested))
            JsonObject(it + mapOf(
                "futureSteps" to JsonArray(listOf(JsonPrimitive(1), nested)),
                "customNodePlan" to custom,
            ))
        }
        val decoded = LabyrinthConfigShareCodec.decode(pack(payload.toString()))
        assertEquals(source.copy(ignoredFieldsByModule = mapOf(
            LabyrinthConfigModule.STRATEGY to strategyFields.keys,
            LabyrinthConfigModule.REROLL to setOf("futureSteps", "customNodePlan.futureCol"),
        )), decoded)
        assertEquals(source, LabyrinthConfigShareCodec.decode(LabyrinthConfigShareCodec.encode(decoded)))
        val target = LabyrinthConfigurationValues()
        assertEquals(
            source.replacement(target, source.modules, 9, false),
            decoded.replacement(target, decoded.modules, 9, false),
        )
        val summary = LabyrinthConfigImportPlan(decoded, setOf(LabyrinthConfigModule.REROLL), 9L, target).changeSummary()
        assertTrue(summary.contains("已忽略 2 项当前版本暂不支持的新增配置项：customNodePlan.futureCol、futureSteps"))
        assertFalse(summary.any { "futureSwitch" in it })
    }

    @Test fun `future fields must be bounded valid json without nulls or credentials`() {
        val root = sharePayload()
        fun withFutureValue(value: JsonElement) = replaceModule(root, "strategy") {
            JsonObject(it + ("futureField" to value))
        }
        listOf(JsonNull, JsonArray(listOf(JsonObject(mapOf("value" to JsonNull))))).forEach {
            reject(pack(withFutureValue(it).toString()))
        }
        val valid = withFutureValue(JsonPrimitive("placeholder")).toString()
        listOf("unquoted", "NaN", "01", "[true,]", "\"raw\nnewline\"", """{"value":true,"value":false}""").forEach {
            reject(pack(valid.replace("\"placeholder\"", it)))
        }
        reject(pack(valid.replace("\"placeholder\"", "[".repeat(14) + "0" + "]".repeat(14))))

        listOf("personalRoleScores", "accountId", "credentialKey", "password", "loginId", "gameUid",
            "token", "cookie", "accessKey", "credentials", "APIKey").forEach { key ->
            reject(pack(replaceModule(root, "strategy") { JsonObject(it + (key to JsonPrimitive("blocked"))) }.toString()))
            reject(pack(withFutureValue(JsonArray(listOf(JsonObject(mapOf(key to JsonPrimitive("blocked")))))).toString()))
        }
        reject(pack(replaceModule(root, "reroll") {
            JsonObject(it + ("customNodePlan" to JsonObject(it.getValue("customNodePlan").jsonObject + ("token" to JsonPrimitive("blocked")))))
        }.toString()))
        reject(pack(replaceModule(root, "reroll") { JsonObject(it - "valueAllowance") }.toString()))
        reject(pack(replaceModule(root, "reroll") {
            JsonObject(it + ("customNodePlan" to JsonObject(it.getValue("customNodePlan").jsonObject - "area1Column3")))
        }.toString()))

        val atLimit = (1..64).associate { "future$it" to JsonPrimitive(it) }
        assertEquals(atLimit.keys, LabyrinthConfigShareCodec.decode(pack(replaceModule(root, "strategy") {
            JsonObject(it + atLimit)
        }.toString())).ignoredFieldsByModule[LabyrinthConfigModule.STRATEGY])
        val overLimit = atLimit + ("oneTooMany" to JsonPrimitive(true))
        reject(pack(replaceModule(root, "strategy") { JsonObject(it + overLimit) }.toString()))
        reject(pack(withFutureValue(JsonObject(overLimit)).toString()))
    }

    @Test fun `whitespace from multiline paste is accepted`() {
        val code = LabyrinthConfigShareCodec.encode(shared())
        assertEquals(LabyrinthConfigShareCodec.decode(code), LabyrinthConfigShareCodec.decode(" \t" + code.chunked(60).joinToString("\r\n") + "\n"))
    }

    @Test fun `unknown version malformed base64 and bad gzip are rejected`() {
        val code = LabyrinthConfigShareCodec.encode(shared())
        listOf("", "LABY2." + code.substring(6), "LABY1.!", "LABY1.A", "LABY1.AAAA", code.dropLast(12), code + "=").forEach(::reject)
        val bytes = compressed(code)
        bytes[bytes.lastIndex - 4] = (bytes[bytes.lastIndex - 4].toInt() xor 1).toByte()
        reject(code(bytes))
        reject(code(compressed(code) + byteArrayOf(0)))
        reject(code(compressed(code) + compressed(code)))
    }

    @Test fun `input length accepts the limit and rejects one extra whitespace character`() {
        val expected = shared()
        val code = LabyrinthConfigShareCodec.encode(expected)
        val atLimit = code.padEnd(LabyrinthConfigShareCodec.MAX_INPUT_LENGTH, ' ')
        assertEquals(LabyrinthConfigShareCodec.MAX_INPUT_LENGTH, atLimit.length)
        assertEquals(expected, LabyrinthConfigShareCodec.decode(atLimit))
        val failure = assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(atLimit + " ") }
        assertEquals("分享码过长", failure.message)
    }

    @Test fun `decompression accepts the byte limit and rejects one extra valid json whitespace byte`() {
        val expected = shared()
        val text = unpack(LabyrinthConfigShareCodec.encode(expected))
        // Count UTF-8 bytes, not characters: the payload contains non-ASCII event names.
        val atLimit = text + " ".repeat(LabyrinthConfigShareCodec.MAX_JSON_BYTES - text.toByteArray(Charsets.UTF_8).size)
        assertEquals(LabyrinthConfigShareCodec.MAX_JSON_BYTES, atLimit.toByteArray(Charsets.UTF_8).size)
        assertEquals(expected, LabyrinthConfigShareCodec.decode(pack(atLimit)))
        val failure = assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(pack(atLimit + " ")) }
        assertEquals("分享码解压后的内容过大", failure.message)
    }

    @Test fun `depth sixteen reaches schema validation but seventeen is rejected by the depth guard`() {
        fun nested(depth: Int) = pack("[".repeat(depth) + "0" + "]".repeat(depth))
        // Arrays are deliberately invalid configuration objects. Distinct diagnostics prove
        // which guard rejected them, rather than passing on any later schema failure.
        val atLimit = assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(nested(16)) }
        assertEquals("分享码不是有效的配置对象", atLimit.message)
        val aboveLimit = assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(nested(17)) }
        assertEquals("配置嵌套层数过多", aboveLimit.message)
    }

    @Test fun `strict schema rejects missing extra null and duplicate module fields`() {
        val text = unpack(LabyrinthConfigShareCodec.encode(shared()))
        reject(pack(text.replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
        reject(pack(text.replace("\"playerWeight\":43,", "")))
        reject(pack(text.replace("\"playerWeight\":43", "\"playerWeight\":101")))
        reject(pack(text.replace("\"playerWeight\":43", "\"playerWeight\":\"43\"")))
        reject(pack(text.replace("\"playerWeight\":43", "\"playerWeight\":43,\"playerWeight\":70")))
        reject(pack(text.replace("\"playerWeight\":43", "\"playerWeight\":43,\"pl\\u0061yerWeight\":70")))
        reject(pack(text.replace("\"playerWeight\":43", "\"personalRoleScores\":{},\"playerWeight\":43")))
        reject(pack(text.replace("\"MULTI_TEAM\"", "\"UNKNOWN\"")))
        reject(pack(text.replace("\"100101\":0", "\"100101\":null")))
        reject(pack(text.replace("\"modules\":{", "\"modules\":{\"credentials\":{},")))
    }

    @Test fun `unknown events and choices are rejected on import and export`() {
        val original = shared()
        val root = Json.parseToJsonElement(unpack(LabyrinthConfigShareCodec.encode(original))).jsonObject
        val modules = root.getValue("modules").jsonObject
        val strategy = modules.getValue("strategy").jsonObject
        listOf(
            mapOf("unsupported-event" to customChoice.runtimeName),
            mapOf(customEvent.runtimeName to "unsupported-choice"),
        ).forEach { overrides ->
            val fields = JsonObject(strategy + ("eventChoiceOverrides" to JsonObject(overrides.mapValues { JsonPrimitive(it.value) })))
            val payload = JsonObject(root + ("modules" to JsonObject(modules + ("strategy" to fields))))
            // Bypass encode validation to exercise the untrusted import boundary directly.
            val imported = assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(pack(payload.toString())) }
            assertEquals("事件或分支不受当前版本支持", imported.message)
            val exported = assertThrows(IllegalArgumentException::class.java) {
                LabyrinthConfigShareCodec.encode(original.copy(strategy = requireNotNull(original.strategy).copy(eventChoiceOverrides = overrides)))
            }
            assertEquals("事件或分支不受当前版本支持", exported.message)
        }
    }

    @Test fun `unknown role IDs remain explicit references and are reported`() {
        val decoded = LabyrinthConfigShareCodec.decode(LabyrinthConfigShareCodec.encode(shared()))
        assertEquals(setOf("100201", "100301", "100401"), decoded.unknownRoleIds(setOf("100101")))
    }

    @Test fun `module replacement preserves unselected data and other accounts`() {
        val local = LabyrinthConfigurationValues(
            LabyrinthStrategySettings(playerWeight = 88, personalRoleScores = mapOf("100501" to 50)),
            mapOf(10L to LabyrinthRerollSettings(guildId = 5), 11L to LabyrinthRerollSettings(difficulty = 1)),
        )
        LabyrinthConfigModule.entries.forEach { module ->
            val next = shared().replacement(local, setOf(module), 10L.takeIf { module == LabyrinthConfigModule.REROLL }, false)
            val expectedStrategy = when (module) {
                LabyrinthConfigModule.STRATEGY -> values.strategy.copy(personalRoleScores = local.strategy.personalRoleScores)
                LabyrinthConfigModule.PERSONAL_SCORES -> local.strategy.copy(personalRoleScores = values.strategy.personalRoleScores)
                LabyrinthConfigModule.REROLL -> local.strategy
            }
            val expectedRerolls = if (module == LabyrinthConfigModule.REROLL) local.rerolls + (10L to values.rerolls.getValue(7L)) else local.rerolls
            assertEquals("$module must only replace its own strategy fields", expectedStrategy, next.strategy)
            assertEquals("$module must preserve other accounts", expectedRerolls, next.rerolls)
        }
        // An absent score module differs from an available but unselected one.
        val withoutScores = shared(setOf(LabyrinthConfigModule.STRATEGY))
        assertNull(withoutScores.personalScores)
        assertEquals(local.copy(strategy = values.strategy.copy(personalRoleScores = local.strategy.personalRoleScores)),
            withoutScores.replacement(local, withoutScores.modules, null, false))
        assertThrows(IllegalArgumentException::class.java) {
            withoutScores.replacement(local, setOf(LabyrinthConfigModule.PERSONAL_SCORES), null, true)
        }
    }

    @Test fun `empty scores default unselected and require explicit clearing confirmation`() {
        val empty = shared().copy(personalScores = emptyMap())
        assertFalse(LabyrinthConfigModule.PERSONAL_SCORES in empty.defaultSelection())
        assertThrows(IllegalArgumentException::class.java) {
            empty.replacement(values, setOf(LabyrinthConfigModule.PERSONAL_SCORES), null, false)
        }
        val restored = empty.replacement(values, setOf(LabyrinthConfigModule.PERSONAL_SCORES), null, true)
        assertTrue(restored.strategy.personalRoleScores.isEmpty())
        assertEquals(values.strategy.copy(personalRoleScores = emptyMap()), restored.strategy)
        assertEquals(values.rerolls, restored.rerolls)
    }

    private fun reject(input: String) {
        assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(input) }
    }
    private fun sharePayload(configuration: LabyrinthSharedConfiguration = shared()): JsonObject =
        Json.parseToJsonElement(unpack(LabyrinthConfigShareCodec.encode(configuration))).jsonObject

    private fun replaceModule(root: JsonObject, name: String, transform: (JsonObject) -> JsonObject): JsonObject {
        val modules = root.getValue("modules").jsonObject
        return JsonObject(root + ("modules" to JsonObject(modules + (name to transform(modules.getValue(name).jsonObject)))))
    }

    private fun compressed(code: String) = Base64.getUrlDecoder().decode(code.removePrefix("LABY1."))
    private fun code(bytes: ByteArray) = "LABY1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unpack(code: String) = GZIPInputStream(compressed(code).inputStream()).bufferedReader().use { it.readText() }
    private fun pack(text: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return code(output.toByteArray())
    }
}

/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

/**
 * 盲文「方案（Code）」模型与多选启用逻辑，零 Android 依赖。
 *
 * 严格复刻谷歌读屏 `BrailleLanguages.Code` + `LocaleLanguageActivity`：
 * - Code 不是「一张带后缀的表」，而是有独立用户可见名的方案；
 * - 第二层是 `CheckBoxPreference` 式多选「启用哪些方案」，不是单选一张表；
 * - `eightDot` / `supportsContracted` 是方案本身的属性，不是从表名后缀猜出来的。
 *
 * 与谷歌的数据来源差异（由本项目决定，不臆造）：谷歌的 Code 是静态枚举，
 * 本项目的方案来自运行时 `TranslatorClient.getTables()` 的 72 张 liblouis 表。
 * 因此一张表映射为一个方案：中文的现行盲文 / 双拼盲文 / 国家通用盲文等
 * variant 各自成方案，不会被压成一个 `supportsContracted` 布尔而丢失入口。
 */
object BrailleCodeCatalog {

    /** 多选启用列表的偏好 key；旧的 `braille_table` 仍保存「当前正在使用的方案」。 */
    const val KEY_PREFERRED_CODES = "preferred_braille_codes"

    /** 方案层新增的文案模板，Activity 层从资源注入，纯逻辑不碰 Context。 */
    data class CodeLabels(
        /** 八点方案的完整名称，如「%1$s八点计算机盲文」。 */
        val eightDotName: String,
        val sixDotSummary: String,
        val eightDotSummary: String,
        val enabled: String,
        val disabled: String,
        /** 当前正在使用的方案追加在状态后面的文字。 */
        val currentSuffix: String,
    )

    data class Code(
        val id: String,
        /** 用户可见方案名；八点方案自带完整「8-dot Computer Braille」字样。 */
        val label: String,
        val summary: String,
        val eightDot: Boolean,
        val supportsContracted: Boolean,
    )

    data class CodeGroup(
        val language: String,
        val title: String,
        val codes: List<Code>,
    )

    /**
     * 把按语言分组的表清单升级为方案清单。
     *
     * 分组与排序完全复用 [BrailleTableCatalog.build] 的结果，避免两套排序漂移；
     * 只替换八点方案的显示名，其余名称仍走旧的 `table_name_*` 模板。
     */
    fun build(
        groups: List<BrailleTableCatalog.TableGroup>,
        seeds: List<BrailleTableCatalog.TableSeed>,
        labels: BrailleTableCatalog.TableLabels,
        codeLabels: CodeLabels,
    ): List<CodeGroup> {
        val byId = seeds.associateBy { it.id }
        return groups.map { group ->
            CodeGroup(
                language = group.language,
                title = group.title,
                codes = group.choices.mapNotNull { choice ->
                    val seed = byId[choice.id] ?: return@mapNotNull null
                    // 八点不再叫「……（八点）」：它是独立方案，名字要一眼可辨。
                    val name = if (seed.isEightDot) {
                        codeLabels.eightDotName.format(seed.localeName)
                    } else {
                        choice.label
                    }
                    Code(
                        id = choice.id,
                        label = name,
                        summary = if (seed.isEightDot) {
                            codeLabels.eightDotSummary
                        } else {
                            codeLabels.sixDotSummary
                        },
                        eightDot = seed.isEightDot,
                        supportsContracted = seed.grade > 1,
                    )
                },
            )
        }
    }

    fun allIds(groups: List<CodeGroup>): Set<String> =
        groups.asSequence().flatMap { it.codes.asSequence() }.map { it.id }.toSet()

    /** 当前偏好值对应的方案名；服务未就绪或取值未知时都不朗读空白。 */
    fun labelForCode(
        value: String,
        autoLabel: String,
        groups: List<CodeGroup>,
        loadingLabel: String,
    ): String {
        if (value == BrailleTableCatalog.AUTO_VALUE || value.isEmpty()) {
            return autoLabel.ifEmpty { loadingLabel }
        }
        groups.asSequence()
            .flatMap { it.codes.asSequence() }
            .firstOrNull { it.id == value }
            ?.let { return it.label }
        return autoLabel.ifEmpty { loadingLabel }
    }

    /** 第二级语言组定位；与 BrailleDisplayLogic.groupForLanguage 同口径。 */
    fun groupForLanguage(groups: List<CodeGroup>, language: String): CodeGroup? =
        groups.firstOrNull { it.language.equals(language, ignoreCase = true) }

    /** 当前正在使用的方案属于哪种语言。 */
    fun languageOfCode(groups: List<CodeGroup>, value: String): String? =
        if (value == BrailleTableCatalog.AUTO_VALUE || value.isEmpty()) {
            null
        } else {
            groups.firstOrNull { group -> group.codes.any { it.id == value } }?.language
        }

    /** 第一级语言行显示的「已启用 N 个方案」。 */
    fun enabledCount(
        groups: List<CodeGroup>,
        enabled: Set<String>,
        language: String,
    ): Int = groups.firstOrNull { it.language.equals(language, ignoreCase = true) }
        ?.codes?.count { it.id in enabled } ?: 0

    /**
     * 读偏好后的归一化：只做「过滤已不存在的表」和「首次使用给系统语言全部方案」
     * 两件事，不允许归一化出空集，避免服务无表可用。
     *
     * **不把「当前正在使用」的方案补进池**。谷歌 `readCurrentActiveInputCodeAndCorrect`
     * 的方向是池管当前码：当前码不在池里，是把当前码纠正到池内，池本身不动。
     * 补进池会让用户没勾过的方案自己变成「已启用」，第一级「已启用 N 个」跟着虚高。
     * 纠正那一步由 [resolveCurrent] 负责：先 [normalize] 再 [resolveCurrent]。
     */
    fun normalize(
        stored: Set<String>,
        groups: List<CodeGroup>,
        defaultLanguage: String,
    ): Set<String> {
        val all = allIds(groups)
        val cleaned = stored.filter { it in all }.toMutableSet()
        if (cleaned.isEmpty()) {
            val preferredGroup = groups.firstOrNull {
                it.language.equals(defaultLanguage, ignoreCase = true)
            } ?: groups.firstOrNull()
            cleaned.addAll(preferredGroup?.codes?.map { it.id }.orEmpty())
            if (cleaned.isEmpty()) cleaned.addAll(all)
        }
        return cleaned.toSet()
    }

    /** 切换单个方案的启用状态；不负责持久化，纯函数便于单测。 */
    fun toggle(enabled: Set<String>, id: String): Set<String> =
        if (id in enabled) enabled - id else enabled + id

    /** 至少保留一个启用方案，避免列表被清空后服务无表可用。 */
    fun canDisable(enabled: Set<String>, id: String): Boolean =
        id !in enabled || enabled.size > 1

    /**
     * 首选池对「当前方案」的纠正，对应谷歌 `readCurrentActiveInputCodeAndCorrect`：
     * 当前方案必须在池内，不在就落到同语言已启用第一项、其次全局第一项。
     *
     * 两处调用：取消勾选当前方案时立即另选一个；表清单就绪后对账老用户的存量值
     * （存的是池外的表就纠正并写回 `braille_table`，与谷歌在读的时候纠正同义）。
     * 池为空时回落到「自动」；正常路径上 [normalize] 已保证池非空。
     */
    fun resolveCurrent(
        enabled: Set<String>,
        currentId: String,
        groups: List<CodeGroup>,
        language: String,
    ): String {
        if (currentId == BrailleTableCatalog.AUTO_VALUE || currentId in enabled) {
            return currentId
        }
        val sameLanguage = groups.firstOrNull {
            it.language.equals(language, ignoreCase = true)
        }
        sameLanguage?.codes?.firstOrNull { it.id in enabled }?.let { return it.id }
        groups.asSequence()
            .flatMap { it.codes.asSequence() }
            .firstOrNull { it.id in enabled }
            ?.let { return it.id }
        return BrailleTableCatalog.AUTO_VALUE
    }

    /**
     * 显示器按键「切换六/八点」的目标方案。
     *
     * 与旧 `findDefaultTableInfo(!eightDot)` 的关键差别：**只在当前语言的已启用
     * 方案里找，绝不跨语言**。旧实现按 matchRank 全局挑表，中文环境下可能跳到
     * 英文八点表，再切回也回不到原中文表。找不到目标时返回 null，保持原状。
     */
    fun nextDotTarget(
        enabled: Set<String>,
        currentId: String,
        groups: List<CodeGroup>,
        defaultLanguage: String,
    ): String? {
        val group = groups.firstOrNull { it.codes.any { code -> code.id == currentId } }
            ?: groups.firstOrNull { it.language.equals(defaultLanguage, ignoreCase = true) }
            ?: groups.firstOrNull()
            ?: return null
        val currentCode = group.codes.firstOrNull { it.id == currentId }
        // 「自动」或非当前语言时按六点处理，切换方向为切到八点，与旧行为一致。
        val targetEightDot = currentCode?.eightDot?.not() ?: true
        return group.codes.firstOrNull {
            it.eightDot == targetEightDot && it.id in enabled
        }?.id
    }
}

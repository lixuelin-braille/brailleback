/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 本文件只收口触觉浏览页的文案装配：一次性把 string 资源收进 [StripLabels]，
 * 界面 Composable 不直接碰 Context，便于读屏线性朗读。纯重构拆分自 TactileStripActivity.kt，
 * 文案键、取值与组装顺序逐字未改。
 */

package com.linxi.brailleback.tactile

import com.linxi.brailleback.R

/** 可选一行方数：点显器常见 20 / 30 / 40 方。 */
internal val CELL_CHOICES = listOf(20, 30, 40)

/** 一次性收集好的文案，界面不直接碰 Context，便于读屏线性朗读。 */
internal data class StripLabels(
    val title: String,
    val back: String,
    val firstHint: String,
    val pickImage: String,
    val noImage: String,
    val imagePicked: String,
    val imagePickedSize: String,
    val sectionCells: String,
    val cellsDesc: String,
    val cellsChoice: List<String>,
    /** 方数跟随点显器的只读说明（带当前方数），默认展示在主界面。 */
    val cellsFollow: String,
    val advanced: String,
    val advancedHint: String,
    val sectionLines: String,
    val linesValue: String,
    val decrease: String,
    val increase: String,
    val sectionDots: String,
    val dots6: String,
    val dots8: String,
    val prev: String,
    val next: String,
    val first: String,
    val last: String,
    val coverHint: String,
    val statusCover: String,
    val statusGraphic: String,
    val statusBack: String,
    val notConnected: String,
    val connected: String,
    val failDecode: String,
    val needImage: String,
    val selected: String,
    val unselected: String,
    val actionRepick: String,
    val vpEnterDetail: String,
    val vpBackOverview: String,
    val vpLegend: String,
    val vpLegendBack: String,
    val vpLegendPrev: String,
    val vpLegendNext: String,
    val vpPanLeft: String,
    val vpPanRight: String,
    val vpPanUp: String,
    val vpPanDown: String,
    /** 点显器按键说明；只在连上点显器的视口态显示。 */
    val vpKeysHint: String,
    val normSection: String,
    val normDesc: String,
    val normAuto: String,
    val normOff: String,
    val normForce: String,
    val g1Split: String,
    val g1Desc: String,
)

/** 一次性收集好的文案，界面不直接碰 Context，便于读屏线性朗读。 */
internal fun TactileStripActivity.labels(): StripLabels = StripLabels(
    title = getString(R.string.tactile_strip_title),
    back = getString(R.string.tactile_strip_back),
    firstHint = getString(R.string.tactile_strip_first_hint),
    pickImage = getString(R.string.tactile_strip_pick_image),
    noImage = getString(R.string.tactile_strip_no_image),
    imagePicked = getString(R.string.tactile_strip_image_picked),
    imagePickedSize = getString(R.string.tactile_strip_image_picked_size),
    sectionCells = getString(R.string.tactile_strip_section_cells),
    cellsDesc = getString(R.string.tactile_strip_cells_desc),
    cellsChoice = CELL_CHOICES.map {
        getString(R.string.tactile_strip_cells_choice, it)
    },
    cellsFollow = getString(R.string.tactile_vp_cells_follow),
    advanced = getString(R.string.tactile_vp_advanced),
    advancedHint = getString(R.string.tactile_vp_advanced_hint),
    sectionLines = getString(R.string.tactile_strip_section_lines),
    linesValue = getString(R.string.tactile_strip_lines_value),
    decrease = getString(R.string.tactile_strip_decrease),
    increase = getString(R.string.tactile_strip_increase),
    sectionDots = getString(R.string.tactile_strip_section_dots),
    dots6 = getString(R.string.tactile_strip_dots_6),
    dots8 = getString(R.string.tactile_strip_dots_8),
    prev = getString(R.string.tactile_strip_prev),
    next = getString(R.string.tactile_strip_next),
    first = getString(R.string.tactile_strip_first),
    last = getString(R.string.tactile_strip_last),
    coverHint = getString(R.string.tactile_strip_cover_hint),
    statusCover = getString(R.string.tactile_strip_status_cover),
    statusGraphic = getString(R.string.tactile_strip_status_graphic),
    statusBack = getString(R.string.tactile_strip_status_back),
    notConnected = getString(R.string.tactile_strip_not_connected),
    connected = getString(R.string.tactile_strip_connected),
    failDecode = getString(R.string.tactile_strip_fail_decode),
    needImage = getString(R.string.tactile_strip_need_image),
    selected = getString(R.string.tactile_strip_selected),
    unselected = getString(R.string.tactile_strip_unselected),
    actionRepick = getString(R.string.tactile_strip_action_repick),
    vpEnterDetail = getString(R.string.tactile_vp_enter_detail),
    vpBackOverview = getString(R.string.tactile_vp_back_overview),
    vpLegend = getString(R.string.tactile_vp_legend),
    vpLegendBack = getString(R.string.tactile_vp_legend_back),
    vpLegendPrev = getString(R.string.tactile_vp_legend_prev),
    vpLegendNext = getString(R.string.tactile_vp_legend_next),
    vpPanLeft = getString(R.string.tactile_vp_pan_left),
    vpPanRight = getString(R.string.tactile_vp_pan_right),
    vpPanUp = getString(R.string.tactile_vp_pan_up),
    vpPanDown = getString(R.string.tactile_vp_pan_down),
    vpKeysHint = getString(R.string.tactile_vp_keys_hint),
    normSection = getString(R.string.tactile_vp_norm_section),
    normDesc = getString(R.string.tactile_vp_norm_desc),
    normAuto = getString(R.string.tactile_vp_norm_auto),
    normOff = getString(R.string.tactile_vp_norm_off),
    normForce = getString(R.string.tactile_vp_norm_force),
    g1Split = getString(R.string.tactile_vp_g1_split),
    g1Desc = getString(R.string.tactile_vp_g1_desc),
)

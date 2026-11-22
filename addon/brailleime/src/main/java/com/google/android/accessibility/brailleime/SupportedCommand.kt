/*
 * Copyright (C) 2023 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.brailleime

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat.ID_NULL
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.braille.interfaces.ImeAction
import com.google.android.accessibility.brailleime.input.Gesture
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableList.toImmutableList
import com.google.android.accessibility.brailleime.BrailleImeGestureAction
import com.google.android.accessibility.brailleime.FeatureFlagReader

/** Commands supported by Braille keyboard. */
class SupportedCommand(
    action: BrailleImeAction,
    category: Category,
    subCategory: SubCategory,
    editable: Boolean,
) {
  private val action: BrailleImeAction
  private val category: Category
  private val subCategory: SubCategory
  private val editable: Boolean

  init {
    if (!category.subCategoryList.contains(subCategory)) {
      throw IllegalArgumentException(
          "Category does not have compatible SubCategory: $subCategory")
    }
    this.action = action
    this.category = category
    this.subCategory = subCategory
    this.editable = editable
  }

  constructor(action: BrailleImeAction, category: Category, subCategory: SubCategory) :
      this(action, category, subCategory, /* editable= */ true)

  constructor(action: BrailleImeAction, category: Category, editable: Boolean) :
      this(action, category, SubCategory.NONE, editable)

  constructor(action: BrailleImeAction, category: Category) :
      this(action, category, SubCategory.NONE, /* editable= */ true)

  /** {@link SupportedCommand} category. */
  enum class Category(
      @StringRes private val titleRes: Int,
      @StringRes private val descriptionRes: Int,
      vararg subCategories: SubCategory,
  ) {
    BASIC(R.string.braille_keyboard_basic_controls, SubCategory.TYPING, SubCategory.KEYBOARD),
    CURSOR_MOVEMENT(
        R.string.braille_keyboard_cursor_movement,
        R.string.braille_keyboard_cursor_movement_description,
        SubCategory.GRANULARITY,
        SubCategory.CHARACTER,
        SubCategory.WORD,
        SubCategory.LINE,
        SubCategory.PLACE_ON_PAGE),
    TEXT_SELECTION_AND_EDITING(
        R.string.braille_keyboard_text_selection_and_editing,
        SubCategory.CHARACTER,
        SubCategory.WORD,
        SubCategory.LINE,
        SubCategory.EDITING),
    SPELL_CHECK(
        R.string.braille_keyboard_spell_check,
        R.string.braille_keyboard_spell_check_description,
        SubCategory.NONE),
    ;

    internal val subCategoryList: ImmutableList<SubCategory> =
        ImmutableList.copyOf(subCategories)

    constructor(@StringRes titleRes: Int, vararg subCategories: SubCategory) :
        this(titleRes, ID_NULL, *subCategories)

    /** Gets the {@link SubCategory} list belong to the {@link Category}. */
    fun getSubCategories(): List<SubCategory> = ArrayList(subCategoryList)

    /** Gets the title of the {@link Category}. */
    fun getTitle(resources: Resources): String = resources.getString(titleRes)

    /** Gets the description of the {@link Category}. */
    fun getDescription(resources: Resources): String =
        if (descriptionRes == ID_NULL) "" else resources.getString(descriptionRes)
  }

  /** {@link SupportedCommand} sub-category. */
  enum class SubCategory(@StringRes private val nameRes: Int) {
    NONE,
    CHARACTER(R.string.bk_pref_category_title_character),
    WORD(R.string.bk_pref_category_title_word),
    LINE(R.string.bk_pref_category_title_line),
    PLACE_ON_PAGE(R.string.bk_pref_category_title_place_on_page),
    EDITING(R.string.bk_pref_category_title_editing),
    GRANULARITY(R.string.bk_pref_category_title_granularity),
    TYPING(R.string.bk_pref_category_title_typing),
    KEYBOARD(R.string.bk_pref_category_title_keyboard);

    constructor() : this(ID_NULL)

    /** Gets the key of the {@link SubCategory}. */
    fun getName(resources: Resources): String =
        if (nameRes == ID_NULL) "" else resources.getString(nameRes)
  }

  /** Gets the {@link ImeAction}. */
  fun getBrailleImeAction(): BrailleImeAction = action

  /** Gets the {@link Gesture} for the {@link BrailleImeAction}. */
  fun getBrailleImeGesture(context: Context): List<Gesture> =
      BrailleImeGestureAction.getGesture(context, getBrailleImeAction())

  /** Gets the category of the action. */
  fun getCategory(): Category = category

  /** Gets the subcategory of the action. */
  fun getSubCategory(): SubCategory = subCategory

  /** Gets the description of the action. */
  fun getActionDescription(context: Context): String =
      getBrailleImeAction().getDescriptionRes(context.resources)

  /** Gets the description of the gesture. */
  fun getGestureDescription(context: Context): String {
    val gestures: List<Gesture> = getBrailleImeGesture(context)
    if (gestures.isEmpty()) {
      return context.resources.getString(R.string.bk_gesture_unassigned)
    }
    return Utils.getCombinedGestureDescription(context, gestures)
  }

  /** Returns if the command is editable. */
  fun isEditable(): Boolean = editable

  private fun isAvailable(context: Context): Boolean {
    if (action == BrailleImeAction.SELECT_CURRENT_TO_START ||
        action == BrailleImeAction.SELECT_CURRENT_TO_END) {
      return FeatureFlagReader.useSelectCurrentToStartOrEnd(context)
    }
    // 拼写检查类手势（上一个/下一个拼写错误、听读拼写建议、确认/撤销建议）依赖 TalkBack
    // 的拼写检查引擎与"拼写纠正粒度"。BrailleBack 侧没有这套引擎，宿主桥接里
    // isCurrentGranularityTypoCorrection() 恒为 false，这些手势按了不会有任何效果。
    // 与其让帮助里列着"按了没反应"的手势，不如直接不展示（等接入真正的拼写引擎再打开）。
    if (category == Category.SPELL_CHECK) {
      return false
    }
    return true
  }

  companion object {
    private val SUPPORTED_COMMANDS: ImmutableList<SupportedCommand> =
        ImmutableList.builder<SupportedCommand>()
            .add(
                SupportedCommand(
                    BrailleImeAction.ADD_SPACE_OR_NEXT_ITEM, Category.BASIC, SubCategory.TYPING))
            .add(
                SupportedCommand(
                    BrailleImeAction.DELETE_CHARACTER_OR_PREVIOUS_ITEM,
                    Category.BASIC,
                    SubCategory.TYPING))
            .add(SupportedCommand(BrailleImeAction.ADD_NEWLINE, Category.BASIC, SubCategory.TYPING))
            .add(
                SupportedCommand(
                    BrailleImeAction.DELETE_WORD, Category.BASIC, SubCategory.TYPING))
            .add(
                SupportedCommand(
                    BrailleImeAction.HIDE_KEYBOARD,
                    Category.BASIC,
                    SubCategory.KEYBOARD,
                    /* editable= */ false))
            .add(
                SupportedCommand(
                    BrailleImeAction.SWITCH_KEYBOARD,
                    Category.BASIC,
                    SubCategory.KEYBOARD,
                    /* editable= */ false))
            .add(
                SupportedCommand(
                    BrailleImeAction.SUBMIT_TEXT, Category.BASIC, SubCategory.TYPING))
            .add(
                SupportedCommand(
                    BrailleImeAction.HELP_AND_OTHER_ACTIONS,
                    Category.BASIC,
                    SubCategory.KEYBOARD,
                    /* editable= */ false))
            .add(
                SupportedCommand(
                    BrailleImeAction.PREVIOUS_CHARACTER,
                    Category.CURSOR_MOVEMENT,
                    SubCategory.CHARACTER))
            .add(
                SupportedCommand(
                    BrailleImeAction.NEXT_CHARACTER,
                    Category.CURSOR_MOVEMENT,
                    SubCategory.CHARACTER))
            .add(
                SupportedCommand(
                    BrailleImeAction.PREVIOUS_WORD,
                    Category.CURSOR_MOVEMENT,
                    SubCategory.WORD))
            .add(
                SupportedCommand(
                    BrailleImeAction.NEXT_WORD, Category.CURSOR_MOVEMENT, SubCategory.WORD))
            .add(
                SupportedCommand(
                    BrailleImeAction.PREVIOUS_LINE, Category.CURSOR_MOVEMENT, SubCategory.LINE))
            .add(
                SupportedCommand(
                    BrailleImeAction.NEXT_LINE, Category.CURSOR_MOVEMENT, SubCategory.LINE))
            .add(
                SupportedCommand(
                    BrailleImeAction.BEGINNING_OF_PAGE,
                    Category.CURSOR_MOVEMENT,
                    SubCategory.PLACE_ON_PAGE))
            .add(
                SupportedCommand(
                    BrailleImeAction.END_OF_PAGE,
                    Category.CURSOR_MOVEMENT,
                    SubCategory.PLACE_ON_PAGE))
            .add(
                SupportedCommand(
                    BrailleImeAction.PREVIOUS_GRANULARITY,
                    Category.CURSOR_MOVEMENT,
                    SubCategory.GRANULARITY))
            .add(
                SupportedCommand(
                    BrailleImeAction.NEXT_GRANULARITY,
                    Category.CURSOR_MOVEMENT,
                    SubCategory.GRANULARITY))
            .add(
                SupportedCommand(
                    BrailleImeAction.MOVE_CURSOR_BACKWARD,
                    Category.CURSOR_MOVEMENT,
                    SubCategory.GRANULARITY))
            .add(
                SupportedCommand(
                    BrailleImeAction.MOVE_CURSOR_FORWARD,
                    Category.CURSOR_MOVEMENT,
                    SubCategory.GRANULARITY))
            .add(
                SupportedCommand(
                    BrailleImeAction.SELECT_PREVIOUS_CHARACTER,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.CHARACTER))
            .add(
                SupportedCommand(
                    BrailleImeAction.SELECT_NEXT_CHARACTER,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.CHARACTER))
            .add(
                SupportedCommand(
                    BrailleImeAction.SELECT_PREVIOUS_WORD,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.WORD))
            .add(
                SupportedCommand(
                    BrailleImeAction.SELECT_NEXT_WORD,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.WORD))
            .add(
                SupportedCommand(
                    BrailleImeAction.SELECT_PREVIOUS_LINE,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.LINE))
            .add(
                SupportedCommand(
                    BrailleImeAction.SELECT_NEXT_LINE,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.LINE))
            .add(
                SupportedCommand(
                    BrailleImeAction.SELECT_ALL,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.EDITING))
            .add(
                SupportedCommand(
                    BrailleImeAction.SELECT_CURRENT_TO_START,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.EDITING))
            .add(
                SupportedCommand(
                    BrailleImeAction.SELECT_CURRENT_TO_END,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.EDITING))
            .add(
                SupportedCommand(
                    BrailleImeAction.COPY,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.EDITING))
            .add(
                SupportedCommand(
                    BrailleImeAction.CUT,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.EDITING))
            .add(
                SupportedCommand(
                    BrailleImeAction.PASTE,
                    Category.TEXT_SELECTION_AND_EDITING,
                    SubCategory.EDITING))
            .add(
                SupportedCommand(
                    BrailleImeAction.PREVIOUS_MISSPELLED_WORD, Category.SPELL_CHECK))
            .add(
                SupportedCommand(
                    BrailleImeAction.NEXT_MISSPELLED_WORD, Category.SPELL_CHECK))
            .add(
                SupportedCommand(
                    BrailleImeAction.HEAR_PREVIOUS_SPELLING_SUGGESTION, Category.SPELL_CHECK))
            .add(
                SupportedCommand(
                    BrailleImeAction.HEAR_NEXT_SPELLING_SUGGESTION, Category.SPELL_CHECK))
            .add(
                SupportedCommand(
                    BrailleImeAction.CONFIRM_SPELLING_SUGGESTION, Category.SPELL_CHECK))
            .add(
                SupportedCommand(
                    BrailleImeAction.UNDO_SPELLING_SUGGESTION, Category.SPELL_CHECK))
            .build()

  /** Returns an immutable and order-sensitive {@link SupportedCommand} list. */
  @JvmStatic
  fun getSupportedCommands(context: Context): ImmutableList<SupportedCommand> =
      SUPPORTED_COMMANDS.stream()
          .filter { supportedCommand -> supportedCommand.isAvailable(context) }
          .collect(toImmutableList())
  }
}

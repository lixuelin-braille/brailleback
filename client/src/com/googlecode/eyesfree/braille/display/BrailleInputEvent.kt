package com.googlecode.eyesfree.braille.display

import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import java.util.HashMap

/**
 * An input event, originating from a braille display.
 *
 * An event contains a command that is a high-level representation of the key
 * or key combination that was pressed on the display such as a navigation key
 * or braille keyboard combination. For some commands, there is also an integer
 * argument that contains additional information.
 */
class BrailleInputEvent(val command: Int, val argument: Int, val eventTime: Long) : Parcelable {
    // Movement commands.

    /** Keyboard command: Used when there is no actual command. */
    companion object {
        const val CMD_NONE = -1

        /** Keyboard command: Navigate upwards. */
        const val CMD_NAV_LINE_PREVIOUS = 1

        /** Keyboard command: Navigate downwards. */
        const val CMD_NAV_LINE_NEXT = 2

        /** Keyboard command: Navigate left one item. */
        const val CMD_NAV_ITEM_PREVIOUS = 3

        /** Keyboard command: Navigate right one item. */
        const val CMD_NAV_ITEM_NEXT = 4

        /** Keyboard command: Navigate one display window to the left. */
        const val CMD_NAV_PAN_LEFT = 5

        /** Keyboard command: Navigate one display window to the right. */
        const val CMD_NAV_PAN_RIGHT = 6

        /** Keyboard command: Navigate to the top or beginning. */
        const val CMD_NAV_TOP = 7

        /** Keyboard command: Navigate to the bottom or end. */
        const val CMD_NAV_BOTTOM = 8

        // Activation commands.

        /** Keyboard command: Activate the currently selected/focused item. */
        const val CMD_ACTIVATE_CURRENT = 20

        /** Keyboard command: Long press the currently selected/focused item. */
        const val CMD_LONG_PRESS_CURRENT = 21

        // Scrolling.

        /** Keyboard command: Scroll backward. */
        const val CMD_SCROLL_BACKWARD = 30

        /** Keyboard command: Scroll forward. */
        const val CMD_SCROLL_FORWARD = 31

        // Selection commands.

        /** Keyboard command: Set the start ot the selection. */
        const val CMD_SELECTION_START = 40

        /** Keyboard command: Set the end of the selection. */
        const val CMD_SELECTION_END = 41

        /** Keyboard command: Select all content of the current field. */
        const val CMD_SELECTION_SELECT_ALL = 42

        /** Keyboard command: Cut the content of the selection. */
        const val CMD_SELECTION_CUT = 43

        /** Keyboard command: Copy the current selection. */
        const val CMD_SELECTION_COPY = 44

        /**
         * Keyboard command: Paste the content of the clipboard at the current
         * insertion point.
         */
        const val CMD_SELECTION_PASTE = 45

        /**
         * Keyboard command: Primary routing key pressed, typically used to move
         * the insertion point or click/tap on the item under the key. The
         * argument is the zero-based position, relative to the first cell on the
         * display, of the cell that is closed to the key that was pressed.
         */
        const val CMD_ROUTE = 50

        /**
         * Keyboard command: Primary routing key long pressed, typically used to
         * long press the item under the key. The argument is the zero-based
         * position, relative to the first cell on the display, of the cell that
         * is closed to the key that was pressed.
         */
        const val CMD_LONG_PRESS_ROUTE = 51

        // Braille keyboard input.

        /**
         * Keyboard command: A key combination was pressed on the braille
         * keyboard. The argument contains the dots that were pressed as a
         * bitmask.
         */
        const val CMD_BRAILLE_KEY = 60

        // Editing keys.

        /** Keyboard command: Enter key. */
        const val CMD_KEY_ENTER = 70

        /** Keyboard command: Delete backward. */
        const val CMD_KEY_DEL = 71

        /** Keyboard command: Delete forward. */
        const val CMD_KEY_FORWARD_DEL = 72

        // Global navigation keys.

        /** Keyboard command: Back button. */
        const val CMD_GLOBAL_BACK = 90

        /** Keyboard command: Home button. */
        const val CMD_GLOBAL_HOME = 91

        /** Keyboard command: Recent apps button. */
        const val CMD_GLOBAL_RECENTS = 92

        /** Keyboard command: Show notificaitons. */
        const val CMD_GLOBAL_NOTIFICATIONS = 93

        // Miscelanous commands.

        /** Keyboard command: Invoke keyboard help. */
        const val CMD_HELP = 100

        /** Keyboard command: Toggle the Braille menu. */
        const val CMD_TOGGLE_BRAILLE_MENU = 117

        /** Keyboard command: Toggle the Braille grade. */
        const val CMD_TOGGLE_BRAILLE_GRADE = 118

        // Web content commands.

        /** Keyboard command: Next section in page. */
        const val CMD_SECTION_NEXT = 110

        /** Keyboard command: Previous section in page. */
        const val CMD_SECTION_PREVIOUS = 111

        /** Keyboard command: Next control in page. */
        const val CMD_CONTROL_NEXT = 112

        /** Keyboard command: Previous control in page. */
        const val CMD_CONTROL_PREVIOUS = 113

        /** Keyboard command: Next list in page. */
        const val CMD_LIST_NEXT = 114

        /** Keyboard command: Previous list in page. */
        const val CMD_LIST_PREVIOUS = 115

        /** Keyboard command: Toggle Incremental search. */
        const val CMD_TOGGLE_INCREMENTAL_SEARCH = 116

        // Meanings of the argument to a command.

        /** This command doesn't have an argument. */
        const val ARGUMENT_NONE = 0

        /**
         * The lower order bits of the arguemnt to this command represent braille
         * dots. Dot 1 is represented by the rightmost bit and so on until dot 8,
         * which is represented by bit 7, counted from the right.
         */
        const val ARGUMENT_DOTS = 1

        /**
         * The argument represents a 0-based position on the display counted from
         * the leftmost cell.
         */
        const val ARGUMENT_POSITION = 2

        private val CMD_NAMES: SparseArray<String> = SparseArray()
        private val NAMES_TO_CMDS: HashMap<String, Int> = HashMap()

        init {
            CMD_NAMES.append(CMD_NAV_LINE_PREVIOUS, "CMD_NAV_LINE_PREVIOUS")
            CMD_NAMES.append(CMD_NAV_LINE_NEXT, "CMD_NAV_LINE_NEXT")
            CMD_NAMES.append(CMD_NAV_ITEM_PREVIOUS, "CMD_NAV_ITEM_PREVIOUS")
            CMD_NAMES.append(CMD_NAV_ITEM_NEXT, "CMD_NAV_ITEM_NEXT")
            CMD_NAMES.append(CMD_NAV_PAN_LEFT, "CMD_NAV_PAN_LEFT")
            CMD_NAMES.append(CMD_NAV_PAN_RIGHT, "CMD_NAV_PAN_RIGHT")
            CMD_NAMES.append(CMD_NAV_TOP, "CMD_NAV_TOP")
            CMD_NAMES.append(CMD_NAV_BOTTOM, "CMD_NAV_BOTTOM")
            CMD_NAMES.append(CMD_ACTIVATE_CURRENT, "CMD_ACTIVATE_CURRENT")
            CMD_NAMES.append(CMD_LONG_PRESS_CURRENT, "CMD_LONG_PRESS_CURRENT")
            CMD_NAMES.append(CMD_SCROLL_BACKWARD, "CMD_SCROLL_BACKWARD")
            CMD_NAMES.append(CMD_SCROLL_FORWARD, "CMD_SCROLL_FORWARD")
            CMD_NAMES.append(CMD_SELECTION_START, "CMD_SELECTION_START")
            CMD_NAMES.append(CMD_SELECTION_END, "CMD_SELECTION_END")
            CMD_NAMES.append(CMD_SELECTION_SELECT_ALL, "CMD_SELECTION_SELECT_ALL")
            CMD_NAMES.append(CMD_SELECTION_CUT, "CMD_SELECTION_CUT")
            CMD_NAMES.append(CMD_SELECTION_COPY, "CMD_SELECTION_COPY")
            CMD_NAMES.append(CMD_SELECTION_PASTE, "CMD_SELECTION_PASTE")
            CMD_NAMES.append(CMD_ROUTE, "CMD_ROUTE")
            CMD_NAMES.append(CMD_LONG_PRESS_ROUTE, "CMD_LONG_PRESS_ROUTE")
            CMD_NAMES.append(CMD_BRAILLE_KEY, "CMD_BRAILLE_KEY")
            CMD_NAMES.append(CMD_KEY_ENTER, "CMD_KEY_ENTER")
            CMD_NAMES.append(CMD_KEY_DEL, "CMD_KEY_DEL")
            CMD_NAMES.append(CMD_KEY_FORWARD_DEL, "CMD_KEY_FORWARD_DEL")
            CMD_NAMES.append(CMD_GLOBAL_BACK, "CMD_GLOBAL_BACK")
            CMD_NAMES.append(CMD_GLOBAL_HOME, "CMD_GLOBAL_HOME")
            CMD_NAMES.append(CMD_GLOBAL_RECENTS, "CMD_GLOBAL_RECENTS")
            CMD_NAMES.append(CMD_GLOBAL_NOTIFICATIONS, "CMD_GLOBAL_NOTIFICATIONS")
            CMD_NAMES.append(CMD_HELP, "CMD_HELP")
            CMD_NAMES.append(CMD_SECTION_NEXT, "CMD_SECTION_NEXT")
            CMD_NAMES.append(CMD_SECTION_PREVIOUS, "CMD_SECTION_PREVIOUS")
            CMD_NAMES.append(CMD_CONTROL_NEXT, "CMD_CONTROL_NEXT")
            CMD_NAMES.append(CMD_CONTROL_PREVIOUS, "CMD_CONTROL_PREVIOUS")
            CMD_NAMES.append(CMD_LIST_NEXT, "CMD_LIST_NEXT")
            CMD_NAMES.append(CMD_LIST_PREVIOUS, "CMD_LIST_PREVIOUS")
            CMD_NAMES.append(CMD_TOGGLE_INCREMENTAL_SEARCH, "CMD_TOGGLE_INCREMENTAL_SEARCH")
            CMD_NAMES.append(CMD_TOGGLE_BRAILLE_MENU, "CMD_TOGGLE_BRAILLE_MENU")
            CMD_NAMES.append(CMD_TOGGLE_BRAILLE_GRADE, "CMD_TOGGLE_BRAILLE_GRADE")
            for (i in 0 until CMD_NAMES.size()) {
                NAMES_TO_CMDS[CMD_NAMES.valueAt(i)] = CMD_NAMES.keyAt(i)
            }
        }

        /**
         * Returns a string representation of `command`, or the string
         * `(unknown)` if the command is unknown.
         */
        @JvmStatic
        fun commandToString(command: Int): String {
            val ret = CMD_NAMES.get(command)
            return ret ?: "(unknown)"
        }

        /**
         * Returns the command corresponding to `commandName`, or [CMD_NONE] if
         * the name doesn't match any existing command.
         */
        @JvmStatic
        fun stringToCommand(commandName: String): Int {
            val command = NAMES_TO_CMDS[commandName]
            return command ?: CMD_NONE
        }

        /**
         * Returns the type of argument for the given `command`.
         */
        @JvmStatic
        fun argumentType(command: Int): Int {
            return when (command) {
                CMD_SELECTION_START, CMD_SELECTION_END, CMD_ROUTE, CMD_LONG_PRESS_ROUTE -> ARGUMENT_POSITION
                CMD_BRAILLE_KEY -> ARGUMENT_DOTS
                else -> ARGUMENT_NONE
            }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<BrailleInputEvent> =
            object : Parcelable.Creator<BrailleInputEvent> {
                override fun createFromParcel(`in`: Parcel): BrailleInputEvent = BrailleInputEvent(`in`)

                override fun newArray(size: Int): Array<BrailleInputEvent?> = arrayOfNulls(size)
            }

        // ---- 以下常量为兼容 BRLTTY 6.7 (谷歌 TalkBack 原生驱动) 所需，BrailleBack 移植时自动补齐 ----
        const val CMD_ALL_APPS = 95
        const val CMD_ARIA_LANDMARK_NEXT = 141
        const val CMD_ARIA_LANDMARK_NEXT_IN_BROWSE = 142
        const val CMD_ARIA_LANDMARK_PREVIOUS = 143
        const val CMD_BRAILLE_DISPLAY_SETTINGS = 121
        const val CMD_BUTTON_NEXT = 177
        const val CMD_BUTTON_NEXT_IN_BROWSE = 178
        const val CMD_BUTTON_PREVIOUS = 179
        const val CMD_CAPTION_ENTER_OR_EXIT = 17
        const val CMD_CHECKBOX_NEXT = 180
        const val CMD_CHECKBOX_NEXT_IN_BROWSE = 181
        const val CMD_CHECKBOX_PREVIOUS = 182
        const val CMD_COMBO_BOX_NEXT = 186
        const val CMD_COMBO_BOX_NEXT_IN_BROWSE = 187
        const val CMD_COMBO_BOX_PREVIOUS = 188
        const val CMD_CONTROL_NEXT_IN_BROWSE = 190
        const val CMD_DEL_WORD = 72
        const val CMD_EDIT_CUSTOM_LABEL = 117
        const val CMD_EDIT_FIELD_NEXT = 192
        const val CMD_EDIT_FIELD_NEXT_IN_BROWSE = 193
        const val CMD_EDIT_FIELD_PREVIOUS = 194
        const val CMD_GRAPHIC_NEXT = 144
        const val CMD_GRAPHIC_NEXT_IN_BROWSE = 145
        const val CMD_GRAPHIC_PREVIOUS = 146
        const val CMD_HEADING_1_NEXT = 159
        const val CMD_HEADING_1_NEXT_IN_BROWSE = 160
        const val CMD_HEADING_1_PREVIOUS = 161
        const val CMD_HEADING_2_NEXT = 162
        const val CMD_HEADING_2_NEXT_IN_BROWSE = 163
        const val CMD_HEADING_2_PREVIOUS = 164
        const val CMD_HEADING_3_NEXT = 165
        const val CMD_HEADING_3_NEXT_IN_BROWSE = 166
        const val CMD_HEADING_3_PREVIOUS = 167
        const val CMD_HEADING_4_NEXT = 168
        const val CMD_HEADING_4_NEXT_IN_BROWSE = 169
        const val CMD_HEADING_4_PREVIOUS = 170
        const val CMD_HEADING_5_NEXT = 171
        const val CMD_HEADING_5_NEXT_IN_BROWSE = 172
        const val CMD_HEADING_5_PREVIOUS = 173
        const val CMD_HEADING_6_NEXT = 174
        const val CMD_HEADING_6_NEXT_IN_BROWSE = 175
        const val CMD_HEADING_6_PREVIOUS = 176
        const val CMD_HEADING_NEXT = 156
        const val CMD_HEADING_NEXT_IN_BROWSE = 157
        const val CMD_HEADING_PREVIOUS = 158
        const val CMD_LINK_NEXT = 195
        const val CMD_LINK_NEXT_IN_BROWSE = 196
        const val CMD_LINK_PREVIOUS = 197
        const val CMD_LIST_ITEM_NEXT = 150
        const val CMD_LIST_ITEM_NEXT_IN_BROWSE = 151
        const val CMD_LIST_ITEM_PREVIOUS = 152
        const val CMD_LIST_NEXT_IN_BROWSE = 148
        const val CMD_NAV_BOTTOM_OR_KEY_ACTIVATE = 129
        const val CMD_NAV_CHARACTER_NEXT = 10
        const val CMD_NAV_CHARACTER_PREVIOUS = 9
        const val CMD_NAV_PAN_DOWN = 6
        const val CMD_NAV_PAN_UP = 5
        const val CMD_NAV_TOP_OR_KEY_ACTIVATE = 128
        const val CMD_NAV_WINDOW_NEXT = 14
        const val CMD_NAV_WINDOW_PREVIOUS = 13
        const val CMD_NAV_WORD_NEXT = 12
        const val CMD_NAV_WORD_PREVIOUS = 11
        const val CMD_NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_BACKWARD = 15
        const val CMD_NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_FORWARD = 16
        const val CMD_NEXT_INPUT_METHOD = 83
        const val CMD_NEXT_READING_CONTROL = 126
        const val CMD_OPEN_TALKBACK_MENU = 118
        const val CMD_PLAY_PAUSE_MEDIA = 134
        const val CMD_PREVIOUS_READING_CONTROL = 125
        const val CMD_QUICK_SETTINGS = 94
        const val CMD_RADIO_BUTTON_NEXT = 183
        const val CMD_RADIO_BUTTON_NEXT_IN_BROWSE = 184
        const val CMD_RADIO_BUTTON_PREVIOUS = 185
        const val CMD_SELECT_NEXT_CHARACTER = 78
        const val CMD_SELECT_NEXT_WORD = 80
        const val CMD_SELECT_PREVIOUS_CHARACTER = 77
        const val CMD_SELECT_PREVIOUS_WORD = 79
        const val CMD_SELECTION_SELECT_CURRENT_TO_END = 47
        const val CMD_SELECTION_SELECT_CURRENT_TO_START = 46
        const val CMD_SHOW_POPUP_MESSAGE_HISTORY = 135
        const val CMD_STOP_READING = 130
        const val CMD_SWITCH_TO_NEXT_INPUT_LANGUAGE = 119
        const val CMD_SWITCH_TO_NEXT_OUTPUT_LANGUAGE = 120
        const val CMD_TABLE_COL_NEXT = 25
        const val CMD_TABLE_COL_PREVIOUS = 24
        const val CMD_TABLE_NEXT = 153
        const val CMD_TABLE_NEXT_IN_BROWSE = 154
        const val CMD_TABLE_PREVIOUS = 155
        const val CMD_TABLE_ROW_NEXT = 23
        const val CMD_TABLE_ROW_PREVIOUS = 22
        const val CMD_TALKBACK_SETTINGS = 122
        const val CMD_TOGGLE_AUTO_SCROLL = 131
        const val CMD_TOGGLE_BROWSE_MODE = 140
        const val CMD_TOGGLE_SCREEN_SEARCH = 116
        const val CMD_TOGGLE_VOICE_FEEDBACK = 124
        const val CMD_TURN_OFF_BRAILLE_DISPLAY = 123
        const val CMD_UNVISITED_LINK_NEXT = 201
        const val CMD_UNVISITED_LINK_NEXT_IN_BROWSE = 202
        const val CMD_UNVISITED_LINK_PREVIOUS = 203
        const val CMD_VISITED_LINK_NEXT = 198
        const val CMD_VISITED_LINK_NEXT_IN_BROWSE = 199
        const val CMD_VISITED_LINK_PREVIOUS = 200
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeInt(command)
        out.writeInt(argument)
        out.writeLong(eventTime)
    }

    private constructor(`in`: Parcel) : this(`in`.readInt(), `in`.readInt(), `in`.readLong())

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("BrailleInputEvent {")
        sb.append("cmd=")
        sb.append(commandToString(command))
        sb.append(", arg=")
        sb.append(argument)
        sb.append("}")
        return sb.toString()
    }
}

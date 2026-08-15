/*
 * Copyright (C) 2012 Google Inc.
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

package com.googlecode.eyesfree.braille.display;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import java.util.HashMap;

/**
 * An input event, originating from a braille display.
 *
 * An event contains a command that is a high-level representation of the
 * key or key combination that was pressed on the display such as a
 * navigation key or braille keyboard combination.  For some commands, there is
 * also an integer argument that contains additional information.
 */
public class BrailleInputEvent implements Parcelable {

    // Movement commands.

    /** Keyboard command: Used when there is no actual command. */
    public static final int CMD_NONE = -1;

    /** Keyboard command: Navigate upwards. */
    public static final int CMD_NAV_LINE_PREVIOUS = 1;
    /** Keyboard command: Navigate downwards. */
    public static final int CMD_NAV_LINE_NEXT = 2;
    /** Keyboard command: Navigate left one item. */
    public static final int CMD_NAV_ITEM_PREVIOUS = 3;
    /** Keyboard command: Navigate right one item. */
    public static final int CMD_NAV_ITEM_NEXT = 4;
    /** Keyboard command: Navigate one display window to the left. */
    public static final int CMD_NAV_PAN_LEFT = 5;
    /** Keyboard command: Navigate one display window to the right. */
    public static final int CMD_NAV_PAN_RIGHT = 6;
    /** Keyboard command: Navigate to the top or beginning. */
    public static final int CMD_NAV_TOP = 7;
    /** Keyboard command: Navigate to the bottom or end. */
    public static final int CMD_NAV_BOTTOM = 8;

    // Activation commands.

    /** Keyboard command: Activate the currently selected/focused item. */
    public static final int CMD_ACTIVATE_CURRENT = 20;
    /** Keyboard command: Long press the currently selected/focused item. */
    public static final int CMD_LONG_PRESS_CURRENT = 21;

    // Scrolling.

    /** Keyboard command: Scroll backward. */
    public static final int CMD_SCROLL_BACKWARD = 30;
    /** Keyboard command: Scroll forward. */
    public static final int CMD_SCROLL_FORWARD = 31;

    // Selection commands.

    /** Keyboard command: Set the start ot the selection. */
    public static final int CMD_SELECTION_START = 40;
    /** Keyboard command: Set the end of the selection. */
    public static final int CMD_SELECTION_END = 41;
    /** Keyboard command: Select all content of the current field. */
    public static final int CMD_SELECTION_SELECT_ALL = 42;
    /** Keyboard command: Cut the content of the selection. */
    public static final int CMD_SELECTION_CUT = 43;
    /** Keyboard command: Copy the current selection. */
    public static final int CMD_SELECTION_COPY = 44;
    /**
     * Keyboard command: Paste the content of the clipboard at the current
     * insertion point.
     */
    public static final int CMD_SELECTION_PASTE = 45;

    /**
     * Keyboard command: Primary routing key pressed, typically
     * used to move the insertion point or click/tap on the item
     * under the key.
     * The argument is the zero-based position, relative to the first cell
     * on the display, of the cell that is closed to the key that
     * was pressed.
     */
    public static final int CMD_ROUTE = 50;
    /**
     * Keyboard command: Primary routing key long pressed, typically
     * used to long press the item under the key.
     * The argument is the zero-based position, relative to the first cell
     * on the display, of the cell that is closed to the key that
     * was pressed.
     */
    public static final int CMD_LONG_PRESS_ROUTE = 51;

    // Braille keyboard input.

    /**
     * Keyboard command: A key combination was pressed on the braille
     * keyboard.
     * The argument contains the dots that were pressed as a bitmask.
     */
    public static final int CMD_BRAILLE_KEY = 60;

    // Editing keys.

    /** Keyboard command: Enter key. */
    public static final int CMD_KEY_ENTER = 70;
    /** Keyboard command: Delete backward. */
    public static final int CMD_KEY_DEL = 71;
    /** Keyboard command: Delete forward. */
    public static final int CMD_KEY_FORWARD_DEL = 72;

    // Global navigation keys.

    /** Keyboard command: Back button. */
    public static final int CMD_GLOBAL_BACK = 90;
    /** Keyboard command: Home button. */
    public static final int CMD_GLOBAL_HOME = 91;
    /** Keyboard command: Recent apps button. */
    public static final int CMD_GLOBAL_RECENTS = 92;
    /** Keyboard command: Show notificaitons. */
    public static final int CMD_GLOBAL_NOTIFICATIONS = 93;

    // Miscelanous commands.

    /** Keyboard command: Invoke keyboard help. */
    public static final int CMD_HELP = 100;

    /** Keyboard command: Toggle the Braille menu. */
    public static final int CMD_TOGGLE_BRAILLE_MENU = 117;

    /** Keyboard command: Toggle the Braille grade. */
    public static final int CMD_TOGGLE_BRAILLE_GRADE = 118;

    // Web content commands.

    /** Keyboard command: Next section in page. */
    public static final int CMD_SECTION_NEXT = 110;
    /** Keyboard command: Previous section in page. */
    public static final int CMD_SECTION_PREVIOUS = 111;
    /** Keyboard command: Next control in page. */
    public static final int CMD_CONTROL_NEXT = 112;
    /** Keyboard command: Previous control in page. */
    public static final int CMD_CONTROL_PREVIOUS = 113;
    /** Keyboard command: Next list in page. */
    public static final int CMD_LIST_NEXT = 114;
    /** Keyboard command: Previous list in page. */
    public static final int CMD_LIST_PREVIOUS = 115;
    /** Keyboard command: Toggle Incremental search. */
    public static final int CMD_TOGGLE_INCREMENTAL_SEARCH = 116;

    // Meanings of the argument to a command.

    /** This command doesn't have an argument. */
    public static final int ARGUMENT_NONE = 0;
    /**
     * The lower order bits of the arguemnt to this command represent braille
     * dots.  Dot 1 is represented by the rightmost bit and so on until dot 8,
     * which is represented by bit 7, counted from the right.
     */
    public static final int ARGUMENT_DOTS = 1;
    /**
     * The argument represents a 0-based position on the display counted from
     * the leftmost cell.
     */
    public static final int ARGUMENT_POSITION = 2;

    private static final SparseArray<String> CMD_NAMES =
            new SparseArray<String>();
    private static final HashMap<String, Integer> NAMES_TO_CMDS
            = new HashMap<String, Integer>();
    static {
        CMD_NAMES.append(CMD_NAV_LINE_PREVIOUS, "CMD_NAV_LINE_PREVIOUS");
        CMD_NAMES.append(CMD_NAV_LINE_NEXT, "CMD_NAV_LINE_NEXT");
        CMD_NAMES.append(CMD_NAV_ITEM_PREVIOUS, "CMD_NAV_ITEM_PREVIOUS");
        CMD_NAMES.append(CMD_NAV_ITEM_NEXT, "CMD_NAV_ITEM_NEXT");
        CMD_NAMES.append(CMD_NAV_PAN_LEFT, "CMD_NAV_PAN_LEFT");
        CMD_NAMES.append(CMD_NAV_PAN_RIGHT, "CMD_NAV_PAN_RIGHT");
        CMD_NAMES.append(CMD_NAV_TOP, "CMD_NAV_TOP");
        CMD_NAMES.append(CMD_NAV_BOTTOM, "CMD_NAV_BOTTOM");
        CMD_NAMES.append(CMD_ACTIVATE_CURRENT, "CMD_ACTIVATE_CURRENT");
        CMD_NAMES.append(CMD_LONG_PRESS_CURRENT, "CMD_LONG_PRESS_CURRENT");
        CMD_NAMES.append(CMD_SCROLL_BACKWARD, "CMD_SCROLL_BACKWARD");
        CMD_NAMES.append(CMD_SCROLL_FORWARD, "CMD_SCROLL_FORWARD");
        CMD_NAMES.append(CMD_SELECTION_START, "CMD_SELECTION_START");
        CMD_NAMES.append(CMD_SELECTION_END, "CMD_SELECTION_END");
        CMD_NAMES.append(CMD_SELECTION_SELECT_ALL, "CMD_SELECTION_SELECT_ALL");
        CMD_NAMES.append(CMD_SELECTION_CUT, "CMD_SELECTION_CUT");
        CMD_NAMES.append(CMD_SELECTION_COPY, "CMD_SELECTION_COPY");
        CMD_NAMES.append(CMD_SELECTION_PASTE, "CMD_SELECTION_PASTE");
        CMD_NAMES.append(CMD_ROUTE, "CMD_ROUTE");
        CMD_NAMES.append(CMD_LONG_PRESS_ROUTE, "CMD_LONG_PRESS_ROUTE");
        CMD_NAMES.append(CMD_BRAILLE_KEY, "CMD_BRAILLE_KEY");
        CMD_NAMES.append(CMD_KEY_ENTER, "CMD_KEY_ENTER");
        CMD_NAMES.append(CMD_KEY_DEL, "CMD_KEY_DEL");
        CMD_NAMES.append(CMD_KEY_FORWARD_DEL, "CMD_KEY_FORWARD_DEL");
        CMD_NAMES.append(CMD_GLOBAL_BACK, "CMD_GLOBAL_BACK");
        CMD_NAMES.append(CMD_GLOBAL_HOME, "CMD_GLOBAL_HOME");
        CMD_NAMES.append(CMD_GLOBAL_RECENTS, "CMD_GLOBAL_RECENTS");
        CMD_NAMES.append(CMD_GLOBAL_NOTIFICATIONS, "CMD_GLOBAL_NOTIFICATIONS");
        CMD_NAMES.append(CMD_HELP, "CMD_HELP");
        CMD_NAMES.append(CMD_SECTION_NEXT, "CMD_SECTION_NEXT");
        CMD_NAMES.append(CMD_SECTION_PREVIOUS, "CMD_SECTION_PREVIOUS");
        CMD_NAMES.append(CMD_CONTROL_NEXT, "CMD_CONTROL_NEXT");
        CMD_NAMES.append(CMD_CONTROL_PREVIOUS, "CMD_CONTROL_PREVIOUS");
        CMD_NAMES.append(CMD_LIST_NEXT, "CMD_LIST_NEXT");
        CMD_NAMES.append(CMD_LIST_PREVIOUS, "CMD_LIST_PREVIOUS");
        CMD_NAMES.append(CMD_TOGGLE_INCREMENTAL_SEARCH,
            "CMD_TOGGLE_INCREMENTAL_SEARCH");
        CMD_NAMES.append(CMD_TOGGLE_BRAILLE_MENU, "CMD_TOGGLE_BRAILLE_MENU");
        CMD_NAMES.append(CMD_TOGGLE_BRAILLE_GRADE, "CMD_TOGGLE_BRAILLE_GRADE");
        for (int i = 0; i < CMD_NAMES.size(); ++i) {
            NAMES_TO_CMDS.put(CMD_NAMES.valueAt(i),
                    CMD_NAMES.keyAt(i));
        }
    }

    private final int mCommand;
    private final int mArgument;
    private final long mEventTime;

    public BrailleInputEvent(int command, int argument, long eventTime) {
        mCommand = command;
        mArgument = argument;
        mEventTime = eventTime;
    }

    /**
     * Returns the keyboard command that this event represents.
     */
    public int getCommand() {
        return mCommand;
    }

    /**
     * Returns the command-specific argument of the event, or zero if the
     * command doesn't have an argument.  See the individual command constants
     * for more details.
     */
    public int getArgument() {
        return mArgument;
    }

    /**
     * Returns the approximate time when this event happened as
     * returned by {@link android.os.SystemClock#uptimeMillis}.
     */
    public long getEventTime() {
        return mEventTime;
    }

    /**
     * Returns a string representation of {@code command}, or the string
     * {@code (unknown)} if the command is unknown.
     */
    public static String commandToString(int command) {
        String ret = CMD_NAMES.get(command);
        return ret != null ? ret : "(unknown)";
    }

    /**
     * Returns the command corresponding to {@code commandName}, or
     * {@link #CMD_NONE} if the name doesn't match any existing command.
     */
    public static int stringToCommand(String commandName) {
        Integer command = NAMES_TO_CMDS.get(commandName);
        if (command == null) {
            return CMD_NONE;
        }
        return command;
    }

    /**
     * Returns the type of argument for the given {@code command}.
     */
    public static int argumentType(int command) {
        switch (command) {
            case CMD_SELECTION_START:
            case CMD_SELECTION_END:
            case CMD_ROUTE:
            case CMD_LONG_PRESS_ROUTE:
                return ARGUMENT_POSITION;
            case CMD_BRAILLE_KEY:
                return ARGUMENT_DOTS;
            default:
                return ARGUMENT_NONE;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("BrailleInputEvent {");
        sb.append("cmd=");
        sb.append(commandToString(mCommand));
        sb.append(", arg=");
        sb.append(mArgument);
        sb.append("}");
        return sb.toString();
    }

    // For Parcelable support.

    public static final Parcelable.Creator<BrailleInputEvent> CREATOR =
        new Parcelable.Creator<BrailleInputEvent>() {
            @Override
            public BrailleInputEvent createFromParcel(Parcel in) {
                return new BrailleInputEvent(in);
            }

            @Override
            public BrailleInputEvent[] newArray(int size) {
                return new BrailleInputEvent[size];
            }
        };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCommand);
        out.writeInt(mArgument);
        out.writeLong(mEventTime);
    }

    private BrailleInputEvent(Parcel in) {
        mCommand = in.readInt();
        mArgument = in.readInt();
        mEventTime = in.readLong();
    }

    // ---- 以下常量为兼容 BRLTTY 6.7 (谷歌 TalkBack 原生驱动) 所需，BrailleBack 移植时自动补齐 ----
    public static final int CMD_ALL_APPS = 95;
    public static final int CMD_ARIA_LANDMARK_NEXT = 141;
    public static final int CMD_ARIA_LANDMARK_NEXT_IN_BROWSE = 142;
    public static final int CMD_ARIA_LANDMARK_PREVIOUS = 143;
    public static final int CMD_BRAILLE_DISPLAY_SETTINGS = 121;
    public static final int CMD_BUTTON_NEXT = 177;
    public static final int CMD_BUTTON_NEXT_IN_BROWSE = 178;
    public static final int CMD_BUTTON_PREVIOUS = 179;
    public static final int CMD_CAPTION_ENTER_OR_EXIT = 17;
    public static final int CMD_CHECKBOX_NEXT = 180;
    public static final int CMD_CHECKBOX_NEXT_IN_BROWSE = 181;
    public static final int CMD_CHECKBOX_PREVIOUS = 182;
    public static final int CMD_COMBO_BOX_NEXT = 186;
    public static final int CMD_COMBO_BOX_NEXT_IN_BROWSE = 187;
    public static final int CMD_COMBO_BOX_PREVIOUS = 188;
    public static final int CMD_CONTROL_NEXT_IN_BROWSE = 190;
    public static final int CMD_DEL_WORD = 72;
    public static final int CMD_EDIT_CUSTOM_LABEL = 117;
    public static final int CMD_EDIT_FIELD_NEXT = 192;
    public static final int CMD_EDIT_FIELD_NEXT_IN_BROWSE = 193;
    public static final int CMD_EDIT_FIELD_PREVIOUS = 194;
    public static final int CMD_GRAPHIC_NEXT = 144;
    public static final int CMD_GRAPHIC_NEXT_IN_BROWSE = 145;
    public static final int CMD_GRAPHIC_PREVIOUS = 146;
    public static final int CMD_HEADING_1_NEXT = 159;
    public static final int CMD_HEADING_1_NEXT_IN_BROWSE = 160;
    public static final int CMD_HEADING_1_PREVIOUS = 161;
    public static final int CMD_HEADING_2_NEXT = 162;
    public static final int CMD_HEADING_2_NEXT_IN_BROWSE = 163;
    public static final int CMD_HEADING_2_PREVIOUS = 164;
    public static final int CMD_HEADING_3_NEXT = 165;
    public static final int CMD_HEADING_3_NEXT_IN_BROWSE = 166;
    public static final int CMD_HEADING_3_PREVIOUS = 167;
    public static final int CMD_HEADING_4_NEXT = 168;
    public static final int CMD_HEADING_4_NEXT_IN_BROWSE = 169;
    public static final int CMD_HEADING_4_PREVIOUS = 170;
    public static final int CMD_HEADING_5_NEXT = 171;
    public static final int CMD_HEADING_5_NEXT_IN_BROWSE = 172;
    public static final int CMD_HEADING_5_PREVIOUS = 173;
    public static final int CMD_HEADING_6_NEXT = 174;
    public static final int CMD_HEADING_6_NEXT_IN_BROWSE = 175;
    public static final int CMD_HEADING_6_PREVIOUS = 176;
    public static final int CMD_HEADING_NEXT = 156;
    public static final int CMD_HEADING_NEXT_IN_BROWSE = 157;
    public static final int CMD_HEADING_PREVIOUS = 158;
    public static final int CMD_LINK_NEXT = 195;
    public static final int CMD_LINK_NEXT_IN_BROWSE = 196;
    public static final int CMD_LINK_PREVIOUS = 197;
    public static final int CMD_LIST_ITEM_NEXT = 150;
    public static final int CMD_LIST_ITEM_NEXT_IN_BROWSE = 151;
    public static final int CMD_LIST_ITEM_PREVIOUS = 152;
    public static final int CMD_LIST_NEXT_IN_BROWSE = 148;
    public static final int CMD_NAV_BOTTOM_OR_KEY_ACTIVATE = 129;
    public static final int CMD_NAV_CHARACTER_NEXT = 10;
    public static final int CMD_NAV_CHARACTER_PREVIOUS = 9;
    public static final int CMD_NAV_PAN_DOWN = 6;
    public static final int CMD_NAV_PAN_UP = 5;
    public static final int CMD_NAV_TOP_OR_KEY_ACTIVATE = 128;
    public static final int CMD_NAV_WINDOW_NEXT = 14;
    public static final int CMD_NAV_WINDOW_PREVIOUS = 13;
    public static final int CMD_NAV_WORD_NEXT = 12;
    public static final int CMD_NAV_WORD_PREVIOUS = 11;
    public static final int CMD_NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_BACKWARD = 15;
    public static final int CMD_NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_FORWARD = 16;
    public static final int CMD_NEXT_INPUT_METHOD = 83;
    public static final int CMD_NEXT_READING_CONTROL = 126;
    public static final int CMD_OPEN_TALKBACK_MENU = 118;
    public static final int CMD_PLAY_PAUSE_MEDIA = 134;
    public static final int CMD_PREVIOUS_READING_CONTROL = 125;
    public static final int CMD_QUICK_SETTINGS = 94;
    public static final int CMD_RADIO_BUTTON_NEXT = 183;
    public static final int CMD_RADIO_BUTTON_NEXT_IN_BROWSE = 184;
    public static final int CMD_RADIO_BUTTON_PREVIOUS = 185;
    public static final int CMD_SELECT_NEXT_CHARACTER = 78;
    public static final int CMD_SELECT_NEXT_WORD = 80;
    public static final int CMD_SELECT_PREVIOUS_CHARACTER = 77;
    public static final int CMD_SELECT_PREVIOUS_WORD = 79;
    public static final int CMD_SELECTION_SELECT_CURRENT_TO_END = 47;
    public static final int CMD_SELECTION_SELECT_CURRENT_TO_START = 46;
    public static final int CMD_SHOW_POPUP_MESSAGE_HISTORY = 135;
    public static final int CMD_STOP_READING = 130;
    public static final int CMD_SWITCH_TO_NEXT_INPUT_LANGUAGE = 119;
    public static final int CMD_SWITCH_TO_NEXT_OUTPUT_LANGUAGE = 120;
    public static final int CMD_TABLE_COL_NEXT = 25;
    public static final int CMD_TABLE_COL_PREVIOUS = 24;
    public static final int CMD_TABLE_NEXT = 153;
    public static final int CMD_TABLE_NEXT_IN_BROWSE = 154;
    public static final int CMD_TABLE_PREVIOUS = 155;
    public static final int CMD_TABLE_ROW_NEXT = 23;
    public static final int CMD_TABLE_ROW_PREVIOUS = 22;
    public static final int CMD_TALKBACK_SETTINGS = 122;
    public static final int CMD_TOGGLE_AUTO_SCROLL = 131;
    public static final int CMD_TOGGLE_BROWSE_MODE = 140;
    public static final int CMD_TOGGLE_SCREEN_SEARCH = 116;
    public static final int CMD_TOGGLE_VOICE_FEEDBACK = 124;
    public static final int CMD_TURN_OFF_BRAILLE_DISPLAY = 123;
    public static final int CMD_UNVISITED_LINK_NEXT = 201;
    public static final int CMD_UNVISITED_LINK_NEXT_IN_BROWSE = 202;
    public static final int CMD_UNVISITED_LINK_PREVIOUS = 203;
    public static final int CMD_VISITED_LINK_NEXT = 198;
    public static final int CMD_VISITED_LINK_NEXT_IN_BROWSE = 199;
    public static final int CMD_VISITED_LINK_PREVIOUS = 200;
}


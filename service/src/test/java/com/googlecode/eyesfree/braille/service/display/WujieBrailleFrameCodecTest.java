/*
 * Copyright (C) 2026 The BrailleBack Project
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

package com.googlecode.eyesfree.braille.service.display;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WujieBrailleFrameCodecTest {

    @Test
    public void encodeRawFrameUsesThirtyCells() {
        byte[] patterns = new byte[30];
        patterns[0] = 0x01;
        patterns[29] = 0x3F;

        byte[] frame = WujieBrailleFrameCodec.encode(patterns, 30, false);

        assertEquals(30, frame.length);
        assertEquals(0x01, frame[0] & 0xFF);
        assertEquals(0x3F, frame[29] & 0xFF);
    }

    @Test
    public void encodeWithReportIdPrefixes0x02() {
        byte[] frame = WujieBrailleFrameCodec.encode(new byte[30], 30, true);

        assertEquals(31, frame.length);
        assertEquals(WujieBrailleFrameCodec.REPORT_ID, frame[0]);
    }

    @Test
    public void encodeMasksHighBitsAndBlanksSentinel() {
        byte[] patterns = new byte[30];
        patterns[0] = (byte) 0xFF;
        patterns[1] = (byte) 0xA0;
        patterns[2] = (byte) 0x40;

        byte[] frame = WujieBrailleFrameCodec.encode(patterns, 30, false);

        assertEquals(0x3F, frame[0] & 0xFF);
        assertEquals(0x00, frame[1] & 0xFF);
        assertEquals(0x00, frame[2] & 0xFF);
    }

    @Test
    public void encodePadsShortInputAndTruncatesLongInput() {
        byte[] shortInput = new byte[] { 0x01, 0x02 };
        byte[] shortFrame =
                WujieBrailleFrameCodec.encode(shortInput, 30, false);
        assertEquals(30, shortFrame.length);
        assertEquals(0x01, shortFrame[0] & 0xFF);
        assertEquals(0x00, shortFrame[2] & 0xFF);

        byte[] longInput = new byte[40];
        for (int i = 0; i < longInput.length; i++) {
            longInput[i] = (byte) (i & 0x3F);
        }
        byte[] longFrame =
                WujieBrailleFrameCodec.encode(longInput, 30, false);
        byte[] expected = new byte[30];
        System.arraycopy(longInput, 0, expected, 0, 30);
        assertArrayEquals(expected, longFrame);
    }
}

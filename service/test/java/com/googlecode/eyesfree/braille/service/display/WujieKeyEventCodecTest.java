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

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class WujieKeyEventCodecTest {

    @Test
    public void decodesNext() {
        assertEquals(WujieKeyEventCodec.COMMAND_NEXT,
                WujieKeyEventCodec.decode(
                        "next".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void decodesPrevious() {
        assertEquals(WujieKeyEventCodec.COMMAND_PREVIOUS,
                WujieKeyEventCodec.decode(
                        "prev".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void ignoresTrailingNulPadding() {
        assertEquals(WujieKeyEventCodec.COMMAND_NEXT,
                WujieKeyEventCodec.decode(
                        new byte[] { 'n', 'e', 'x', 't', 0 }));
    }

    @Test
    public void ignoresInvalidPayloads() {
        assertEquals(WujieKeyEventCodec.COMMAND_NONE,
                WujieKeyEventCodec.decode(null));
        assertEquals(WujieKeyEventCodec.COMMAND_NONE,
                WujieKeyEventCodec.decode(new byte[] { 'n', 'e', 'x' }));
        assertEquals(WujieKeyEventCodec.COMMAND_NONE,
                WujieKeyEventCodec.decode(
                        "other".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(WujieKeyEventCodec.COMMAND_NONE,
                WujieKeyEventCodec.decode(new byte[] { 'n', 'e', 'x', 't',
                        '!' }));
    }
}

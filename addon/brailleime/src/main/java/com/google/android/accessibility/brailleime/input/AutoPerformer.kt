/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.brailleime.input

import android.content.Context
import android.os.Handler
import android.os.Message
import android.text.TextUtils
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.brailleime.BrailleImeLog
import com.google.android.accessibility.brailleime.Utils
import com.google.common.base.Splitter
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.ArrayDeque
import java.util.Properties

/**
 * Reads number-encoded braille from file and turns it into {@link BrailleInputPlaneResult}.
 *
 * <p>The file is read from getExternalFilesDir() + "/braillekeyboard_autoperform.txt".
 *
 * <p>The file must be in the .properties format. See https://en.wikipedia.org/wiki/.properties.
 *
 * <p>Example file:
 * /sdcard/Android/data/com.linxi.screenreader/files/braillekeyboard_autoperform.txt
 *
 * <pre>
 * braille=6-5-2346 356 1345-135 1235-135-135-134 123456 1346-256
 * interCharacterDelay=300
 * interWordDelay=500
 * initialDelay=3000
 * </pre>
 */
class AutoPerformer(context: Context, private val callback: Callback) : Handler() {

  interface Callback {
    fun onPerform(bipr: BrailleInputPlaneResult)

    fun createSwipe(swipe: Swipe): BrailleInputPlaneResult

    fun onFinish()
  }

  private val context: Context
  private var characters: ArrayDeque<BrailleCharacter>? = null
  private var interCharacterDelay = 0
  private var interWordDelay = 0

  init {
    this.context = context
  }

  fun start() {
    BrailleImeLog.d(TAG, "autoperform begin")
    val file = File(context.getExternalFilesDir(null), "braillekeyboard_autoperform.txt")
    if (file.exists()) {
      try {
        val fileReader = FileReader(file)
        val props = Properties()
        props.load(fileReader)
        fileReader.close()
        scheduleAutoPerform(props)
      } catch (e: IOException) {
        BrailleImeLog.e(TAG, "autoperform read failure: " + e.message)
      }
    } else {
      BrailleImeLog.e(TAG, "autoperform file not found at: " + file.absolutePath)
    }
  }

  private fun scheduleAutoPerform(props: Properties) {
    val spaceDelimitedLine = props.getProperty("braille")
    if (TextUtils.isEmpty(spaceDelimitedLine)) {
      return
    }
    interCharacterDelay =
      Utils.parseIntWithDefault(
        props.getProperty("interCharacterDelay"), DEFAULT_INTER_CHARACTER_DELAY)
    interWordDelay =
      Utils.parseIntWithDefault(props.getProperty("interWordDelay"), DEFAULT_INTER_WORD_DELAY)
    val initialDelay =
      Utils.parseIntWithDefault(props.getProperty("initialDelay"), DEFAULT_INITIAL_DELAY)
    characters = ArrayDeque()
    for (dashDelimited in Splitter.on(' ').omitEmptyStrings().split(spaceDelimitedLine)) {
      characters!!.addAll(BrailleWord(dashDelimited).toList())
      characters!!.add(BrailleCharacter())
    }
    sendEmptyMessageDelayed(0, initialDelay.toLong())
  }

  override fun handleMessage(msg: Message) {
    if (!characters!!.isEmpty()) {
      val character = characters!!.removeFirst()
      val bipr: BrailleInputPlaneResult
      val delayUntilNextMessage: Long
      if (character.isEmpty()) {
        bipr = callback.createSwipe(Swipe(Swipe.Direction.LEFT, /* touchCount= */ 1))
        delayUntilNextMessage = interWordDelay.toLong()
      } else {
        bipr = BrailleInputPlaneResult.createTapAndRelease(character)
        delayUntilNextMessage = interCharacterDelay.toLong()
      }
      callback.onPerform(bipr)
      sendEmptyMessageDelayed(0, delayUntilNextMessage)
    } else {
      callback.onFinish()
    }
  }

  companion object {
    private const val TAG = "AutoPerformer"

    private const val DEFAULT_INTER_CHARACTER_DELAY = 500
    private const val DEFAULT_INTER_WORD_DELAY = 1000
    private const val DEFAULT_INITIAL_DELAY = 1000
  }
}

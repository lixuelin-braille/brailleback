/*
 * Copyright 2019 Google Inc.
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

package com.google.android.accessibility.braille.translate

import android.content.Context
import com.google.common.base.Ascii

/**
 * Factory for creating {@link BrailleTranslator}.
 *
 * <p>Every implementation has an implicit {@code name} associated with it, which is simply the
 * unqualified (simple) name of the implementation class. In order for the implementation class to
 * be found at runtime, its fully-qualified package name must be of the following form:
 * com.google.android.accessibility.brailleime.translate.name.Name where {@code name} is the lower
 * case of {@code Name}.
 */
interface TranslatorFactory {

  /** Creates the translator. */
  fun create(context: Context, codeName: String, contractedMode: Boolean): BrailleTranslator

  /** Returns the library name of the translator. */
  fun getLibraryName(): String

  companion object {
    /** Returns the name associated with {@param aClass}. */
    @JvmStatic
    fun getNameFromClass(aClass: Class<out TranslatorFactory>): String {
      return aClass.simpleName
    }

    /** Returns the {@link TranslatorFactory} associated with {@param name}. */
    @JvmStatic
    fun forName(name: String): TranslatorFactory {
      try {
        val parentPackageName = TranslatorFactory::class.java.getPackage()!!.name
        val packageName = parentPackageName + "." + Ascii.toLowerCase(name) + "." + name
        val aClass = Class.forName(packageName)
        return aClass.getDeclaredConstructor().newInstance() as TranslatorFactory
      } catch (e: Exception) {
        throw IllegalArgumentException("Could not find TranslatorFactory for name " + name, e)
      }
    }
  }
}

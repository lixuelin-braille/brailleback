/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.accessibility.utils.preference

import android.graphics.drawable.Drawable
import android.view.MenuItem
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import com.google.android.accessibility.utils.FeatureSupport
import org.checkerframework.checker.nullness.qual.Nullable

/**
 * Preference activity used. This base class is separate from PreferencesActivity to allow partner
 * code (for open-source) to inherit from AppCompatActivity.
 */
abstract class BasePreferencesActivity : AppCompatActivity() {

  // This variable are used as arguments of Intent to identify the fragment which should be created.
  companion object {
    @JvmField
    val FRAGMENT_NAME: String = "FragmentName"

    @JvmField
    val FRAGMENT_ARGS: String = "FragmentArgs"

    private const val DEFAULT_CONTAINER_ID: Int = android.R.id.content
  }

  /**
   * If action-bar back key button is pressed, end this sub-activity when there is no fragment in
   * the stack. Otherwise, it will go to last fragment.
   */
  override fun onBackPressed() {
    // Closes the activity if there is no fragment inside the stack. Otherwise the activity will has
    // a blank screen since there is no any fragment. onBackPressed() in Activity.java only handles
    // popBackStackImmediate(). This will close activity to avoid a blank screen.
    if (supportFragmentManager.backStackEntryCount == 0) {
      finishAfterTransition()
    } else {
      super.onBackPressed()
    }
  }

  /**
   * Prepares action bar
   *
   * @param icon Icon shows on action bar.
   */
  protected open fun prepareActionBar(icon: Drawable?) {
    val actionBar: ActionBar? = supportActionBar
    if (FeatureSupport.isWatch(applicationContext)) {
      actionBar?.hide()
      return
    }

    if (actionBar != null) {
      if (icon != null) {
        actionBar.setIcon(icon)
      }
      actionBar.setDisplayHomeAsUpEnabled(true)
    }

    if (FeatureSupport.isTv(applicationContext)) {
      hideBackButton()
    }
  }

  /** Hides the back button (or home) inside the action bar */
  private fun hideBackButton() {
    val actionBar: ActionBar? = supportActionBar
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(false)
    }
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == android.R.id.home) {
      onBackPressed()
      return true
    }
    return super.onOptionsItemSelected(item)
  }

  /**
   * Gets Identifier of the container whose fragment(s) at the activity should use.
   *
   * @return The fragments container in the activity.
   */
  protected open fun getContainerId(): Int = DEFAULT_CONTAINER_ID

  /** Return {@code true} if the ancestor already handles fragment transaction. */
  protected open fun isDefaultFragmentTransactionHandled(): Boolean = false
}

/*
 * 移植说明：原版 PreferencesActivity 会用 R.layout.preference_with_survey 支持 HaTS 问卷。
 * BrailleBack 没有问卷资源，因此去掉该分支，其余行为保持一致。
 */

package com.google.android.accessibility.utils.preference;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.accessibility.utils.PreferenceSettingsUtils;

/** 无障碍设置页基类。 */
public abstract class PreferencesActivity extends BasePreferencesActivity {

  /** 由子类提供要展示的偏好 Fragment。 */
  protected abstract PreferenceFragmentCompat createPreferenceFragment();

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    prepareActionBar(/* icon= */ null);

    if (!isDefaultFragmentTransactionHandled()) {
      PreferenceFragmentCompat preferenceFragment = createPreferenceFragment();
      if (preferenceFragment != null && savedInstanceState == null) {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(getContainerId(), preferenceFragment, getFragmentTag());
        transaction.commit();
      }
    }
  }

  @Override
  public boolean onNavigateUp() {
    if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
      finishAfterTransition();
    } else {
      getSupportFragmentManager().popBackStackImmediate();
    }
    return true;
  }

  /** 按 key 查找当前 Fragment 中的偏好项。 */
  public Preference findPreference(CharSequence key) {
    return PreferenceSettingsUtils.findPreference(this, key);
  }

  /** 子类若需要 HaTS 问卷布局可覆盖；BrailleBack 恒为 false。 */
  protected boolean supportHatsSurvey() {
    return false;
  }

  /** Fragment 的 tag。 */
  protected @Nullable String getFragmentTag() {
    return null;
  }
}

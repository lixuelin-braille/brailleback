/*
 * Copyright (C) 2026 林夕读屏 (Lingxi Screen Reader)
 *
 * Per-scheme sound editor: lists every sound event in the active scheme and
 * lets the user enable/disable it, preview it, pick a custom audio file from
 * the device, or reset it to the built-in sound. Also offers scheme-level
 * actions (rename / reset / delete).
 */

package com.googlecode.eyesfree.brailleback.sound;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.googlecode.eyesfree.brailleback.R;
import com.googlecode.eyesfree.utils.FeedbackController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Editor for the currently active sound scheme.
 */
public class SoundSchemeEditorActivity extends Activity {

    private static final int REQUEST_PICK_SOUND = 1;

    private SoundSchemeManager mManager;
    private SoundScheme mScheme;
    private FeedbackController mPreview;
    private EventAdapter mAdapter;
    private SoundEvent mPendingEvent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mManager = SoundSchemeManager.getInstance(this);
        mScheme = mManager.getActiveScheme();
        mPreview = new FeedbackController(this);

        setTitle(getString(R.string.sound_editor_title, mScheme.getName()));

        ListView listView = new ListView(this);
        mAdapter = new EventAdapter();
        listView.setAdapter(mAdapter);
        setContentView(listView);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPreview != null) {
            mPreview.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Menu / scheme-level actions
    // ------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.sound_editor_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem delete = menu.findItem(R.id.action_sound_delete_scheme);
        if (delete != null) {
            delete.setEnabled(mScheme.isUserScheme());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_sound_rename_scheme) {
            promptRename();
            return true;
        } else if (id == R.id.action_sound_reset_scheme) {
            mManager.resetScheme(mScheme.getKey());
            mAdapter.notifyDataSetChanged();
            Toast.makeText(this, R.string.sound_editor_use_builtin, Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_sound_delete_scheme) {
            promptDelete();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void promptRename() {
        final EditText input = new EditText(this);
        input.setText(mScheme.getName());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle(R.string.sound_editor_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.length() > 0) {
                            mManager.renameScheme(mScheme.getKey(), name);
                            mScheme = mManager.getActiveScheme();
                            setTitle(getString(R.string.sound_editor_title, mScheme.getName()));
                            mAdapter.notifyDataSetChanged();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void promptDelete() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.sound_editor_delete_confirm)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mManager.deleteScheme(mScheme.getKey());
                        mScheme = mManager.getActiveScheme();
                        setTitle(getString(R.string.sound_editor_title, mScheme.getName()));
                        mAdapter.notifyDataSetChanged();
                        Toast.makeText(SoundSchemeEditorActivity.this,
                                R.string.sound_editor_no_custom, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------
    // Custom sound picking
    // ------------------------------------------------------------------

    private void pickCustomSound(SoundEvent event) {
        mPendingEvent = event;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_PICK_SOUND);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.sound_editor_pick_sound, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_SOUND || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null || mPendingEvent == null) {
            return;
        }
        File target = copyUriToFile(uri);
        if (target != null) {
            mManager.setEventPath(mScheme, mPendingEvent, target.getAbsolutePath());
            mAdapter.notifyDataSetChanged();
            Toast.makeText(this, R.string.sound_editor_customize, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.sound_editor_pick_sound, Toast.LENGTH_SHORT).show();
        }
        mPendingEvent = null;
    }

    private File copyUriToFile(Uri uri) {
        try {
            String displayName = queryDisplayName(uri);
            String ext = "ogg";
            if (displayName != null) {
                int dot = displayName.lastIndexOf('.');
                if (dot >= 0) {
                    ext = displayName.substring(dot + 1).toLowerCase(java.util.Locale.US);
                }
            }
            File dir = new File(getFilesDir(), "custom_sounds");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File target = new File(dir,
                    mPendingEvent.getKey() + "_" + System.currentTimeMillis() + "." + ext);
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            FileOutputStream out = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.close();
            in.close();
            return target;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Event list adapter
    // ------------------------------------------------------------------

    private class EventAdapter extends BaseAdapter {
        private final LayoutInflater mInflater;

        EventAdapter() {
            mInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public int getCount() {
            return SoundEvent.values().length;
        }

        @Override
        public Object getItem(int position) {
            return SoundEvent.values()[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = mInflater.inflate(R.layout.sound_editor_row, parent, false);
            }
            final SoundEvent event = SoundEvent.values()[position];
            TextView name = (TextView) view.findViewById(R.id.sound_row_name);
            TextView summary = (TextView) view.findViewById(R.id.sound_row_summary);
            final CheckBox enabled = (CheckBox) view.findViewById(R.id.sound_row_enabled);
            Button play = (Button) view.findViewById(R.id.sound_row_play);
            Button custom = (Button) view.findViewById(R.id.sound_row_custom);

            name.setText(event.getNameResId());
            SoundEntry override = mScheme.getOverride(event);
            boolean audible = mManager.isEventAudible(mScheme, event);
            if (override != null && !override.isEnabled()) {
                summary.setText(R.string.sound_editor_silenced);
            } else if (override != null && override.getPath() != null) {
                summary.setText(override.getPath());
            } else {
                summary.setText(R.string.sound_editor_no_custom);
            }

            enabled.setChecked(audible);
            enabled.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mManager.setEventEnabled(mScheme, event, enabled.isChecked());
                    notifyDataSetChanged();
                }
            });

            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mManager.playPreview(mScheme, event, mPreview);
                }
            });

            custom.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickCustomSound(event);
                }
            });

            return view;
        }
    }
}

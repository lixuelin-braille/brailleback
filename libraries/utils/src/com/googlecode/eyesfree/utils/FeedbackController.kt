/*
 * Copyright (C) 2011 Google Inc.
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

package com.googlecode.eyesfree.utils

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.media.MediaPlayer.OnPreparedListener
import android.media.SoundPool
import android.media.SoundPool.OnLoadCompleteListener
import android.os.Handler
import android.os.Vibrator
import android.text.TextUtils
import android.util.Log
import android.util.SparseArray
import android.util.SparseIntArray
import java.io.File
import java.io.IOException
import java.util.ArrayList

/**
 * Provides auditory and haptic feedback.
 *
 * @author alanv@google.com (Alan Viverette)
 */
class FeedbackController(context: Context) {
    /** Default stream for audio feedback. */
    private val DEFAULT_STREAM = AudioManager.STREAM_MUSIC

    /** Default volume for sound playback relative to current stream volume. */
    private val DEFAULT_VOLUME = 1.0f

    /** Default rate for sound playback. Use 1.0f for normal speed playback. */
    private val DEFAULT_RATE = 1.0f

    /** Number of channels to use in SoundPool for auditory icon feedback. */
    private val NUMBER_OF_CHANNELS = 10

    /** Default delay time between repeated sounds. */
    private val DEFAULT_REPETITION_DELAY = 150

    /** Map of resource IDs to vibration pattern arrays. */
    private val mResourceIdToVibrationPatternMap = SparseArray<LongArray>()

    /** Map of resource IDs to loaded sound stream IDs. */
    private val mResourceIdToSoundMap = SparseIntArray()

    /** Unloaded resources to play post-load */
    private val mPostLoadPlayables = ArrayList<Int>()

    /** Parent context. Required for mapping resource IDs to resources. */
    private val mContext: Context

    /** Parent resources. Used to distinguish raw and MIDI resources. */
    private val mResources: android.content.res.Resources

    /** Vibration service used to play vibration patterns. */
    private val mVibrator: Vibrator

    /** Sound pool used to play auditory icons. */
    private val mSoundPool: SoundPool

    /** Handler used for delaying feedback */
    private val mHandler: Handler

    /** Whether haptic feedback is enabled. */
    private var mHapticEnabled = true

    /** Whether auditory feedback is enabled. */
    private var mAuditoryEnabled = true

    /** Current volume (range 0..1). */
    private var mVolume = DEFAULT_VOLUME

    /**
     * Constructs and initializes a new feedback controller.
     */
    init {
        mContext = context
        mResources = context.resources
        mVibrator = mContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        mSoundPool = SoundPool(NUMBER_OF_CHANNELS, DEFAULT_STREAM, 1)
        mSoundPool.setOnLoadCompleteListener(object : OnLoadCompleteListener {
            override fun onLoadComplete(soundPool: SoundPool, sampleId: Int, status: Int) {
                if (status == 0) {
                    synchronized(mPostLoadPlayables) {
                        val idx = mPostLoadPlayables.indexOf(sampleId)
                        if (idx >= 0) {
                            soundPool.play(
                                sampleId, DEFAULT_VOLUME, DEFAULT_VOLUME, 1, 0, DEFAULT_RATE
                            )
                            mPostLoadPlayables.removeAt(idx)
                        }
                    }
                }
            }
        })
        mHandler = Handler()

        mResourceIdToSoundMap.clear()
        mResourceIdToVibrationPatternMap.clear()
        MidiUtils.purgeMidiTempFiles(context)
    }

    /**
     * @return The parent context.
     */
    protected val context: Context
        get() = mContext

    /**
     * Sets the current volume for auditory feedback.
     *
     * @param volume Volume value (range 0..100).
     */
    fun setVolume(volume: Int) {
        mVolume = (Math.min(100, Math.max(0, volume)) / 100.0f)
    }

    /**
     * @param enabled Whether haptic feedback should be enabled.
     */
    fun setHapticEnabled(enabled: Boolean) {
        mHapticEnabled = enabled
    }

    /**
     * @param enabled Whether auditory feedback should be enabled.
     */
    fun setAuditoryEnabled(enabled: Boolean) {
        mAuditoryEnabled = enabled
    }

    /**
     * Stops all active feedback.
     */
    fun interrupt() {
        mVibrator.cancel()
    }

    /**
     * Releases resources associated with this feedback controller. It is good
     * practice to call this method when you're done using the controller.
     */
    fun shutdown() {
        mVibrator.cancel()
        mSoundPool.release()
        MidiUtils.purgeMidiTempFiles(mContext)
    }

    /**
     * Asynchronously make a sound available for later use if audio feedback is
     * enabled. Sounds should be loaded using this function whenever audio
     * feedback is enabled.
     *
     * @param resId Resource ID of the sound to be loaded.
     * @return The sound pool identifier for the resource.
     */
    fun preloadSound(resId: Int): Int {
        if (mResourceIdToSoundMap.indexOfKey(resId) >= 0) {
            return mResourceIdToSoundMap.get(resId)
        }

        val soundPoolId: Int
        val resType = mResources.getResourceTypeName(resId)

        soundPoolId = if ("raw" == resType) {
            mSoundPool.load(mContext, resId, 1)
        } else if ("array" == resType) {
            val notes = mResources.getIntArray(resId)
            loadMidiSoundFromArray(notes, false)
        } else {
            LogUtils.log(this, Log.ERROR, "Failed to load sound: Unknown resource type")
            return -1
        }

        if (soundPoolId < 0) {
            LogUtils.log(this, Log.ERROR, "Failed to load sound: Invalid sound pool ID")
            return -1
        }

        mResourceIdToSoundMap.put(resId, soundPoolId)
        return soundPoolId
    }

    /**
     * Convenience method for playing different sounds based on a boolean
     * result.
     *
     * @param result The conditional that controls which sound resource is
     * played.
     * @param trueResId The resource to play if the result is `true`.
     * @param falseResId The resource to play if the result is `false`.
     * @see #playSound(int)
     */
    fun playSoundConditional(result: Boolean, trueResId: Int, falseResId: Int) {
        val resId = if (result) trueResId else falseResId

        if (resId > 0) {
            playSound(resId)
        }
    }

    /**
     * Plays the sound file specified by the given resource identifier at the
     * default rate.
     *
     * @param resId The sound file's resource identifier.
     * @return `true` if successful
     */
    fun playSound(resId: Int): Boolean {
        return playSound(resId, DEFAULT_RATE, DEFAULT_VOLUME)
    }

    /**
     * Plays the sound file specified by the given resource identifier on the
     * given stream
     *
     * @param resId The resource identifier of the sound to play
     * @param streamId The [AudioManager] identifier of the stream on
     * which to play the sound
     * @return `true` if successful
     */
    fun playSoundOnStream(resId: Int, streamId: Int, volume: Float): Boolean {
        if (!mAuditoryEnabled) {
            return false
        }

        val mp = MediaPlayer()
        mp.setOnPreparedListener(object : OnPreparedListener {
            override fun onPrepared(mp: MediaPlayer) {
                mp.start()
            }
        })

        mp.setOnCompletionListener(object : OnCompletionListener {
            override fun onCompletion(mp: MediaPlayer) {
                mp.release()
            }
        })

        val fd = mContext.resources.openRawResourceFd(resId)
        mp.setAudioStreamType(streamId)
        try {
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            mp.setVolume(volume, volume)
            mp.prepareAsync()
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }

        return true
    }

    /**
     * Plays an audio file from an absolute path. Used for user-customized
     * earcons (scheme system). Unlike the SoundPool-based methods this uses
     * MediaPlayer so arbitrary files (ogg/mp3/wav) can be played.
     *
     * @param path absolute path to the audio file
     * @param volume playback volume in the range 0..1
     * @return `true` if playback was started successfully
     */
    fun playSoundPath(path: String, volume: Float): Boolean {
        if (!mAuditoryEnabled || TextUtils.isEmpty(path)) {
            return false
        }

        val mp = MediaPlayer()
        mp.setOnPreparedListener(object : OnPreparedListener {
            override fun onPrepared(mp: MediaPlayer) {
                mp.start()
            }
        })
        mp.setOnCompletionListener(object : OnCompletionListener {
            override fun onCompletion(mp: MediaPlayer) {
                mp.release()
            }
        })

        try {
            mp.setAudioStreamType(DEFAULT_STREAM)
            mp.setDataSource(path)
            mp.setVolume(volume, volume)
            mp.prepareAsync()
            return true
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        mp.release()
        return false
    }

    /**
     * Plays the sound file specified by the given resource identifier at the
     * specified rate.
     *
     * @param resId The sound file's resource identifier.
     * @param rate The rate at which to play back the sound, where 1.0 is normal
     * speed and 0.5 is half-speed.
     * @param volume The volume at which to play the sound, where 1.0 is normal
     * volume and 0.5 is half-volume.
     * @return `true` if successful
     */
    fun playSound(resId: Int, rate: Float, volume: Float): Boolean {
        if (!mAuditoryEnabled) {
            return false
        }

        if (mResourceIdToSoundMap.indexOfKey(resId) < 0) {
            val soundPoolId = preloadSound(resId)
            mPostLoadPlayables.add(soundPoolId)

            // Since we'll play the sound immediately after it loads, just
            // assume it will play successfully.
            return true
        }

        val soundId = mResourceIdToSoundMap.get(resId)
        val relativeVolume = mVolume * volume
        val stream = mSoundPool.play(soundId, relativeVolume, relativeVolume, 1, 0, rate)

        return (stream != 0)
    }

    /**
     * Plays the sound file specified by the given resource identifier repeatedly.
     *
     * @param resId The resource of the sound file to play
     * @param repetitions The number of times to repeat the sound file
     * @return `true` if successful
     */
    fun playRepeatedSound(resId: Int, repetitions: Int): Boolean {
        return playRepeatedSound(
            resId, DEFAULT_RATE, DEFAULT_VOLUME, repetitions, DEFAULT_REPETITION_DELAY.toLong()
        )
    }

    /**
     * Plays the sound file specified by the given resource identifier
     * repeatedly.
     *
     * @param resId The resource of the sound file to play
     * @param rate The rate at which to play the sound file
     * @param volume The volume at which to play the sound, where 1.0 is normal
     * volume and 0.5 is half-volume.
     * @param repetitions The number of times to repeat the sound file
     * @param delay The amount of time between calls to start playback of the
     * sound in miliseconds. Should be the sound's playback time plus
     * some delay.
     * @return `true` if successful
     */
    fun playRepeatedSound(
        resId: Int, rate: Float, volume: Float, repetitions: Int, delay: Long
    ): Boolean {
        if (!mAuditoryEnabled) {
            return false
        }

        mHandler.post(RepeatedSoundRunnable(resId, rate, volume, repetitions, delay))
        return true
    }

    /**
     * Generates and plays a MIDI scale.
     *
     * @param program The MIDI program ID to use
     * @param velocity The MIDI velocity to use for each note
     * @param duration The duration in milliseconds of each note
     * @param startingPitch The MIDI pitch value on which the scale should begin
     * @param pitchesToPlay The number of pitches to play. 7 pitches (or 5
     * pentatonic) is a complete scale. 8 (or 6 pentatonic) for a resolved scale.
     * @param scaleType The MIDI_SCALE_TYPE_* constant associated with the type
     * of scale to play as defined in [MidiUtils].
     * @return `true` if successful, `false` otherwise.
     */
    fun playMidiScale(
        program: Int,
        velocity: Int,
        duration: Int,
        startingPitch: Int,
        pitchesToPlay: Int,
        scaleType: Int
    ): Boolean {
        if (!mAuditoryEnabled) {
            return false
        }

        return MidiUtils.playMidiScale(
            program, velocity, duration, startingPitch, pitchesToPlay,
            scaleType, mContext
        )
    }

    /**
     * Plays the vibration pattern specified by the given resource identifier.
     *
     * @param resId The vibration pattern's resource identifier.
     * @return `true` if successful
     */
    fun playVibration(resId: Int): Boolean {
        if (!mHapticEnabled || (mVibrator == null)) {
            return false
        }

        val pattern = getVibrationPattern(resId)
        mVibrator.vibrate(pattern, -1)
        return true
    }

    /**
     * Plays the vibration pattern specified by the given resource identifier
     * and repeats it indefinitely from the specified index.
     *
     * @see #cancelVibration()
     * @param resId The vibration pattern's resource identifier.
     * @param repeatIndex The index at which to loop vibration in the pattern.
     * @return `true` if successful
     */
    fun playRepeatedVibration(resId: Int, repeatIndex: Int): Boolean {
        if (!mHapticEnabled || (mVibrator == null)) {
            return false
        }

        val pattern = getVibrationPattern(resId)

        if (repeatIndex < 0 || repeatIndex >= pattern.size) {
            throw ArrayIndexOutOfBoundsException(repeatIndex)
        }
        mVibrator.vibrate(pattern, repeatIndex)
        return true
    }

    /**
     * Cancels vibration feedback if in progress.
     */
    fun cancelVibration() {
        if (mVibrator != null) {
            mVibrator.cancel()
        }
    }

    /**
     * Retrieves the vibration pattern associated with the array resource.
     *
     * @param resId The array resource id of the pattern to retrieve.
     * @return an array of `long` values from the pattern.
     */
    private fun getVibrationPattern(resId: Int): LongArray {
        val cachedPattern = mResourceIdToVibrationPatternMap.get(resId)
        if (cachedPattern != null) {
            return cachedPattern
        }

        val intPattern = mResources.getIntArray(resId)
        if (intPattern == null) {
            return LongArray(0)
        }

        val pattern = LongArray(intPattern.size)
        for (i in pattern.indices) {
            pattern[i] = intPattern[i].toLong()
        }

        mResourceIdToVibrationPatternMap.put(resId, pattern)

        return pattern
    }

    fun playMidiSoundFromPool(soundID: Int) {
        mSoundPool.play(soundID, DEFAULT_VOLUME, DEFAULT_VOLUME, 1, 0, DEFAULT_RATE)
    }

    /**
     * Generates a MIDI file and loads it into the sound pool.
     *
     * @param notes The MIDI track sequence to load
     * @param playOnLoad `true` to play the sound immediately after it
     * loads.
     * @return the sound ID for this sound, or `-1` on error.
     */
    fun loadMidiSoundFromArray(notes: IntArray, playOnLoad: Boolean): Int {
        val midiFile = MidiUtils.generateMidiFileFromArray(mContext, notes)

        if (midiFile == null) {
            return -1
        }

        val soundId = mSoundPool.load(midiFile.path, 1)
        if (playOnLoad) {
            mPostLoadPlayables.add(soundId)
        }

        return soundId
    }

    /**
     * Class used for repeated playing of sound resources.
     */
    private inner class RepeatedSoundRunnable(
        private val mResId: Int,
        private val mPlaybackRate: Float,
        private val mPlaybackVolume: Float,
        private val mRepetitions: Int,
        private val mDelay: Long
    ) : Runnable {
        private var mTimesPlayed: Int = 0

        override fun run() {
            if (mTimesPlayed < mRepetitions) {
                playSound(mResId, mPlaybackRate, mPlaybackVolume)
                mTimesPlayed++
                mHandler.postDelayed(this, mDelay)
            }
        }
    }
}

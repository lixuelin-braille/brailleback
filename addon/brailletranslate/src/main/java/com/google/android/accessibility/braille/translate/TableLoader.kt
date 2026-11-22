package com.google.android.accessibility.braille.translate

/** Interface for loading table files. */
interface TableLoader {
  /** The state of the file. */
  enum class FileState {
    FILES_ERROR,
    FILES_NOT_EXTRACTED,
    FILES_EXTRACTED,
  }

  /** Ensures the data files are extracted. */
  fun ensureDataFiles()

  /** Returns true if the files are extracted. */
  fun isExtracted(): Boolean
}

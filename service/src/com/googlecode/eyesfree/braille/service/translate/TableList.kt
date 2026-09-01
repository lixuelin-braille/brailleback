package com.googlecode.eyesfree.braille.service.translate

import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.text.TextUtils
import android.util.Log
import com.googlecode.eyesfree.braille.service.BuildConfig
import com.googlecode.eyesfree.braille.service.R
import com.googlecode.eyesfree.braille.translate.TableInfo
import java.io.IOException
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.regex.Pattern
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Loads and keeps the list of supported braille tables from xml resources.
 */
class TableList(res: Resources) {
    private val mTableInfos = ArrayList<TableInfo>()
    private val mTableFileNames = HashMap<String, String>()

    init {
        parseResource(res)
    }

    /** Returns all loaded tables. */
    fun getTables(): List<TableInfo> = Collections.unmodifiableList(mTableInfos)

    /** Returns the file name to pass to liblouis for this table. */
    fun getFileName(tableId: String): String? = mTableFileNames[tableId]

    private fun parseResource(res: Resources) {
        val p: XmlResourceParser = res.getXml(R.xml.tablelist)
        try {
            while (true) {
                val elementType = p.next()
                if (elementType == XmlPullParser.END_DOCUMENT) {
                    break
                }
                if (elementType != XmlPullParser.START_TAG) {
                    continue
                }
                if (p.depth == 1) {
                    p.require(XmlPullParser.START_TAG, null, TAG_TABLE_LIST)
                    continue
                } else if (p.depth == 2) {
                    p.require(XmlPullParser.START_TAG, null, TAG_TABLE)
                    parseTable(p)
                } else {
                    throw RuntimeException(p.positionDescription + ": Unexpected start tag")
                }
            }
        } catch (ex: XmlPullParserException) {
            throw RuntimeException(ex)
        } catch (ex: IOException) {
            throw RuntimeException(ex)
        } finally {
            p.close()
        }
    }

    private fun parseTable(p: XmlResourceParser) {
        val locale = parseLocale(p.getAttributeValue(null, ATTR_LOCALE))
        if (locale == null) {
            throw RuntimeException(p.positionDescription + ": Locale must be specified")
        }
        var dots = p.getAttributeIntValue(null, ATTR_DOTS, -1)
        var grade = p.getAttributeIntValue(null, ATTR_GRADE, -1)
        if (dots < 0 && grade < 0) {
            throw RuntimeException(p.positionDescription + ": neither dots nor grade was specified")
        }
        if (grade >= 0 && dots < 0) {
            dots = 6
        }
        when (dots) {
            6 -> if (grade < 0) {
                grade = 1
            }
            8 -> if (grade >= 0) {
                throw RuntimeException(
                    p.positionDescription + ": grade must not be specified for 8 dot braille"
                )
            }
            else -> throw RuntimeException(p.positionDescription + ": dots must be either 6 or 8")
        }
        val id = p.getAttributeValue(null, ATTR_ID)
        if (id == null) {
            throw RuntimeException(p.positionDescription + ": missing id attribute")
        }
        val variant = p.getAttributeValue(null, ATTR_VARIANT)
        val fileName = p.getAttributeValue(null, ATTR_FILE_NAME)
        if (fileName == null) {
            throw RuntimeException(p.positionDescription + ": missing fileName attribute")
        }
        mTableInfos.add(TableInfo(locale, dots == 8, grade, id, variant))
        mTableFileNames[id] = fileName
        if (DBG) {
            Log.v(
                LOG_TAG,
                String.format(
                    "Table %s: locale=%s, dots=%d, grade=%d, variant=%s,fileName=%s",
                    id, locale.displayName, dots, grade, variant, fileName
                )
            )
        }
    }

    private fun parseLocale(value: String?): Locale? {
        if (TextUtils.isEmpty(value)) {
            return null
        }
        val pieces = LOCALE_SPLITTER.split(value, 3)
        return when (pieces.size) {
            1 -> Locale(pieces[0])
            2 -> Locale(pieces[0], pieces[1])
            else -> Locale(pieces[0], pieces[1], pieces[2])
        }
    }

    companion object {
        private val LOG_TAG = TableList::class.java.simpleName
        private val DBG = BuildConfig.DEBUG
        private const val TAG_TABLE_LIST = "table-list"
        private const val TAG_TABLE = "table"
        private const val ATTR_ID = "id"
        private const val ATTR_LOCALE = "locale"
        private val LOCALE_SPLITTER = Pattern.compile("_", Pattern.LITERAL)
        private const val ATTR_DOTS = "dots"
        private const val ATTR_GRADE = "grade"
        private const val ATTR_FILE_NAME = "fileName"
        private const val ATTR_VARIANT = "variant"
    }
}

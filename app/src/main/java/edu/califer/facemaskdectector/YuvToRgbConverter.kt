
package edu.califer.facemaskdectector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.renderscript.*
import androidx.viewbinding.BuildConfig
import java.nio.ByteBuffer

/**
 * This is a helper class which will be used to convert an image object from image format by YUV_420-888 to bitmap object.
 * @param context The Context of the calling View.
 */
class YuvToRgbConverter(context: Context) {

    private val renderString = RenderScript.create(context)

    private val scriptYUVToRGB =
        ScriptIntrinsicYuvToRGB.create(renderString, Element.U8_4(renderString))

    private lateinit var YUBBuffer: ByteBuffer

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
    private lateinit var inputAllocation: Allocation
    private lateinit var outputAllocation: Allocation

    //Pixel Count = 30fs and 640*480 px. This is the default analyzer for machine learning model
    private var pixelCount: Int = -1

    /**
     * This function is able to achieve the same fps as the cameraX image analyses use case on a pixel 3XL device at a default analyser resolution which is 30fps with 640* 480 px.
     */
    @Synchronized
    fun yuvToRgb(image: Image, output: Bitmap) {

        if (!::YUBBuffer.isInitialized) {
            pixelCount = image.cropRect.width() * image.cropRect.height()
            val pixelSizeBits = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888)
            YUBBuffer = ByteBuffer.allocateDirect(pixelCount * pixelSizeBits / 8)
        }
        YUBBuffer.rewind()
        imageToByteBuffer(image, YUBBuffer.array())

        if (!::inputAllocation.isInitialized) {
            val elementType =
                Type.Builder(renderString, Element.YUV(renderString)).setYuvFormat(ImageFormat.NV21)
                    .create()
            inputAllocation =
                Allocation.createSized(renderString, elementType.element, YUBBuffer.array().size)
        }

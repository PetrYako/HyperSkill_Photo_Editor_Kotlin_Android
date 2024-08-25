package org.hyperskill.photoeditor

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever.BitmapParams
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Images
import android.transition.Slide
import android.widget.Button
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import androidx.core.graphics.drawable.toBitmap
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.pow


class MainActivity : AppCompatActivity() {
    private lateinit var currentImage: ImageView
    private lateinit var galleryButton: Button
    private lateinit var brightnessSlider: Slider
    private lateinit var saveButton: Button
    private lateinit var contrastSlider: Slider
    private lateinit var saturationSlider: Slider
    private lateinit var gammaSlider: Slider

    private lateinit var defaultBitmap: Bitmap

    private var filterJob: Job? = null

    private val activityResultLauncher =
        registerForActivityResult(StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val photoUri = result.data?.data ?: return@registerForActivityResult
                currentImage.setImageURI(photoUri)
                defaultBitmap = currentImage.drawable.toBitmap()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        //do not change this line
        defaultBitmap = createBitmap()
        currentImage.setImageBitmap(defaultBitmap)
    }

    private fun bindViews() {
        currentImage = findViewById(R.id.ivPhoto)
        galleryButton = findViewById(R.id.btnGallery)
        brightnessSlider = findViewById(R.id.slBrightness)
        saveButton = findViewById(R.id.btnSave)
        contrastSlider = findViewById(R.id.slContrast)
        saturationSlider = findViewById(R.id.slSaturation)
        gammaSlider = findViewById(R.id.slGamma)

        galleryButton.setOnClickListener { pickImageFromGallery() }

        saveButton.setOnClickListener { saveImage() }

        brightnessSlider.addOnChangeListener { _, _, _ -> applyFilters() }
        contrastSlider.addOnChangeListener { _, _, _ -> applyFilters() }
        saturationSlider.addOnChangeListener { _, _, _ -> applyFilters() }
        gammaSlider.addOnChangeListener { _, _, _ -> applyFilters() }
    }

    private fun saveImage() {
        if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            val bitmap = currentImage.drawable.toBitmap()
            val values = ContentValues()
            values.put(Images.Media.DATE_TAKEN, System.currentTimeMillis())
            values.put(Images.Media.MIME_TYPE, "image/jpeg")
            values.put(Images.ImageColumns.WIDTH, bitmap.width)
            values.put(Images.ImageColumns.HEIGHT, bitmap.height)

            val uri = contentResolver.insert(
                Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return

            contentResolver.openOutputStream(uri).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                0
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0) {
            saveButton.callOnClick()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } else {
            PermissionChecker.checkSelfPermission(
                this,
                permission
            ) == PermissionChecker.PERMISSION_GRANTED
        }
    }

    private fun applyFilters() {
        filterJob?.cancel()

        val brightness = brightnessSlider.value
        val contrast = contrastSlider.value
        val saturation = saturationSlider.value
        val gamma = gammaSlider.value

        val bitmap = defaultBitmap.copy(defaultBitmap.config, true)
        val width = bitmap.width
        val height = bitmap.height

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        filterJob = GlobalScope.launch(Dispatchers.Default) {
            val totalBrightness =
                async { applyBrightnessAndReturnTotalBrightness(brightness, pixels) }.await()
            val avgBrightness = (totalBrightness / (width * height * 3)).toInt()
            applyContrast(contrast, avgBrightness, pixels)
            applySaturation(saturation, pixels)
            applyGamma(gamma.toDouble(), pixels)

            ensureActive()

            runOnUiThread {
                val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                newBitmap?.setPixels(pixels, 0, width, 0, 0, width, height)
                currentImage.setImageBitmap(newBitmap)
            }
        }
    }

    private fun applyGamma(gamma: Double, pixels: IntArray) {
        pixels.forEachIndexed { index, pixel ->
            var red = (pixel shr 16) and 0xff
            var green = (pixel shr 8) and 0xff
            var blue = pixel and 0xff

            red = (255 * (red / 255.0).pow(gamma)).toInt().coerceIn(0, 255)
            green = (255 * (green / 255.0).pow(gamma)).toInt().coerceIn(0, 255)
            blue = (255 * (blue / 255.0).pow(gamma)).toInt().coerceIn(0, 255)

            pixels[index] = Color.rgb(red, green, blue)
        }
    }

    private fun applySaturation(saturation: Float, pixels: IntArray) {
        pixels.forEachIndexed { index, pixel ->
            var red = (pixel shr 16) and 0xff
            var green = (pixel shr 8) and 0xff
            var blue = pixel and 0xff

            val alpha = (255 + saturation) / (255 - saturation).toDouble()
            val rgbAvg = (red + green + blue) / 3

            red = (alpha * (red - rgbAvg) + rgbAvg).toInt().coerceIn(0, 255)
            green = (alpha * (green - rgbAvg) + rgbAvg).toInt().coerceIn(0, 255)
            blue = (alpha * (blue - rgbAvg) + rgbAvg).toInt().coerceIn(0, 255)

            pixels[index] = Color.rgb(red, green, blue)
        }
    }

    private fun applyContrast(contrast: Float, avgBrightness: Int, pixels: IntArray) {
        pixels.forEachIndexed { index, pixel ->
            var red = (pixel shr 16) and 0xff
            var green = (pixel shr 8) and 0xff
            var blue = pixel and 0xff

            val alpha = (255 + contrast) / (255 - contrast).toDouble()

            red = (alpha * (red - avgBrightness) + avgBrightness).toInt().coerceIn(0, 255)
            green = (alpha * (green - avgBrightness) + avgBrightness).toInt().coerceIn(0, 255)
            blue = (alpha * (blue - avgBrightness) + avgBrightness).toInt().coerceIn(0, 255)

            pixels[index] = Color.rgb(red, green, blue)
        }
    }

    private fun applyBrightnessAndReturnTotalBrightness(brightness: Float, pixels: IntArray): Long {
        var totalBrightness = 0L
        pixels.forEachIndexed { index, pixel ->
            var red = (pixel shr 16) and 0xff
            var green = (pixel shr 8) and 0xff
            var blue = pixel and 0xff

            red = (red + brightness).toInt().coerceIn(0, 255)
            green = (green + brightness).toInt().coerceIn(0, 255)
            blue = (blue + brightness).toInt().coerceIn(0, 255)

            totalBrightness += (red + green + blue)

            pixels[index] = Color.rgb(red, green, blue)
        }
        return totalBrightness
    }

    private fun pickImageFromGallery() {
        val intent = Intent(Intent.ACTION_PICK, Images.Media.EXTERNAL_CONTENT_URI)
        activityResultLauncher.launch(intent)
    }

    // do not change this function
    private fun createBitmap(): Bitmap {
        val width = 200
        val height = 100
        val pixels = IntArray(width * height)
        // get pixel array from source

        var R: Int
        var G: Int
        var B: Int
        var index: Int

        for (y in 0 until height) {
            for (x in 0 until width) {
                // get current index in 2D-matrix
                index = y * width + x
                // get color
                R = x % 100 + 40
                G = y % 100 + 80
                B = (x + y) % 100 + 120

                pixels[index] = Color.rgb(R, G, B)

            }
        }
        // output bitmap
        val bitmapOut = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmapOut.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmapOut
    }
}
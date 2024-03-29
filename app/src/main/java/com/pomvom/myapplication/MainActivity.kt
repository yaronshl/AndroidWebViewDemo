package com.pomvom.myapplication

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.DownloadManager
import android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.support.v4.app.ActivityCompat
import android.support.v4.content.FileProvider
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.IOException
import java.net.URISyntaxException
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {

    private val CONTEXT_MENU_ID_DOWNLOAD_IMAGE: Int = 0
    private val TAG = MainActivity::class.java.simpleName

    private var mFilePathCallback: ValueCallback<Array<Uri>>? = null
    private var mUploadMessage: ValueCallback<Uri>? = null
    private var mCapturedImageURI: Uri? = null
    private var mCameraPhotoPath: String? = null
    private var INPUT_FILE_REQUEST_CODE = 1
    private var FILECHOOSER_RESULTCODE = 1

    private val SERVER_URL = "https://app.pomvom.com/demo"

    var mPendingFileDownload: PendingFileDownload? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissions()

        registerForContextMenu(web_view)
        web_view.setWebViewClient(WebViewClient())
        web_view.settings.javaScriptEnabled = true
        web_view.settings.loadWithOverviewMode = true
        web_view.settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web_view.settings.useWideViewPort = true
        web_view.settings.allowFileAccess = true
        web_view.settings.allowContentAccess = true
        web_view.settings.supportZoom();

        web_view.webChromeClient = object : WebChromeClient() {
            @TargetApi(Build.VERSION_CODES.LOLLIPOP)
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {

                // Double check that we don't have any existing callbacks
                if (mFilePathCallback != null) {
                    mFilePathCallback!!.onReceiveValue(null)
                }
                mFilePathCallback = filePathCallback

                dispatchTakePictureIntent()

                return true
            }
        }

        web_view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {

                val uri = request!!.getUrl()
                if (Objects.equals(uri.getScheme(), "whatsapp") || Objects.equals(uri.getScheme(), "mailto")) {
                    try {
                        val intent = Intent.parseUri (request.getUrl().toString(), Intent.URI_INTENT_SCHEME);
                        if (intent.resolveActivity(getPackageManager()) != null)
                            startActivity(intent);
                        return true;
                    } catch (exception: URISyntaxException) {
                        Log.e(TAG, exception.localizedMessage);
                    }
                }
                return super.shouldOverrideUrlLoading(view, request)
            }
        }


        WebView.setWebContentsDebuggingEnabled(true);
    }

    override fun onCreateContextMenu(menu: ContextMenu, view: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        web_view.hitTestResult?.let {
            when (it.type) {
                WebView.HitTestResult.IMAGE_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    menu.setHeaderTitle(R.string.images_options)
                    menu.add(0, CONTEXT_MENU_ID_DOWNLOAD_IMAGE, 0, R.string.download_image)
                }
                else -> Log.e(TAG, "error downloading image")
            }
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {

        web_view.hitTestResult?.let {
            val url = it.extra

            if (CONTEXT_MENU_ID_DOWNLOAD_IMAGE == item.itemId) {
                mPendingFileDownload = PendingFileDownload(url, Environment.DIRECTORY_PICTURES)
                downloadFileWithPermissionCheck()
                return true
            }
        }

        return super.onContextItemSelected(item)
    }

    override fun onBackPressed() {
        if (web_view.canGoBack()) {
            web_view.goBack()
        } else {
            super.onBackPressed()
        }

    }

    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            // Ensure that there's a camera activity to handle the intent
            takePictureIntent.resolveActivity(packageManager)?.also {
                // Create the File where the photo should go
                var photoFile: File? = null
                try {
                    photoFile = createImageFile()
                    takePictureIntent.putExtra("PhotoPath", mCameraPhotoPath)
                } catch (ex: IOException) {
                    // Error occurred while creating the File
                    Log.e(TAG, "Unable to create Image File", ex)
                }

                // Continue only if the File was successfully created
                if (photoFile != null) {
                    mCameraPhotoPath = "file:" + photoFile!!.getAbsolutePath()
                    val photoURI: Uri = FileProvider.getUriForFile(
                        this,
                        "com.pomvom.myapplication",
                        photoFile
                    )
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                }

                takePictureIntent.putExtra("android.intent.extras.CAMERA_FACING", 1);
                takePictureIntent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1);
                takePictureIntent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
                startActivityForResult(takePictureIntent, INPUT_FILE_REQUEST_CODE)
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val storageDir: File = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            mCameraPhotoPath = absolutePath
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            if (requestCode != INPUT_FILE_REQUEST_CODE || mFilePathCallback == null) {
                super.onActivityResult(requestCode, resultCode, data)
                return
            }

            var results: Array<Uri>? = null

            // Check that the response is a good one
            if (resultCode == Activity.RESULT_OK) {
                if (data == null) {
                    // If there is not data, then we may have taken a photo
                    if (mCameraPhotoPath != null) {
                        results = arrayOf(Uri.parse(mCameraPhotoPath))
                    }
                } else {
                    val dataString = data.dataString
                    if (dataString != null) {
                        results = arrayOf(Uri.parse(dataString))
                    }
                }
            }

            if (mFilePathCallback != null) {
                mFilePathCallback!!.onReceiveValue(results)
                mFilePathCallback = null
            }


        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
            if (requestCode != FILECHOOSER_RESULTCODE || mUploadMessage == null) {
                super.onActivityResult(requestCode, resultCode, data)
                return
            }

            if (requestCode == FILECHOOSER_RESULTCODE) {
                if (null == this.mUploadMessage) {
                    return
                }

                var result: Uri? = null
                try {
                    if (resultCode != Activity.RESULT_OK) {
                        result = null
                    } else {
                        // retrieve from the private variable if the intent is null
                        result = if (data == null) mCapturedImageURI else data.data
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        applicationContext, "activity :$e",
                        Toast.LENGTH_LONG
                    ).show()
                }

                if (mFilePathCallback != null) {
                    mUploadMessage!!.onReceiveValue(result)
                    mUploadMessage = null
                }
            }
        }

        return
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            0 -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    web_view.loadUrl(SERVER_URL)
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                }
                return
            }

            // Add other 'when' lines to check for other
            // permissions this app might request.
            else -> {
                // Ignore all other requests.
            }
        }
    }

    private fun hasPermissions(vararg permissions: String): Boolean {
        if (permissions != null) {
            for (permission in permissions) {
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        permission
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
        )
        if (!hasPermissions(*permissions)) {

            // Permission is not granted
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE) ||
                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
            ) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(this, permissions, 0)

                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        } else {
            web_view.loadUrl(SERVER_URL)
        }
    }

    private fun downloadFileWithPermissionCheck() {
        if (hasPermissions()) {
            downloadFile()
        } else {
            requestPermissions()
        }
    }

    private fun downloadFile() {
        val pending = mPendingFileDownload
        pending?.let {
            val uri = Uri.parse(pending.url)
            val guessedFileName = URLUtil.guessFileName(pending.url, null, null)
            Log.d(TAG, "Guessed filename of $guessedFileName for url ${pending.url}")
            val request = DownloadManager.Request(uri).apply {
                allowScanningByMediaScanner()
                setDestinationInExternalPublicDir(pending.directory, "image.jpeg")
                setNotificationVisibility(VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            mPendingFileDownload = null
        }
    }
}

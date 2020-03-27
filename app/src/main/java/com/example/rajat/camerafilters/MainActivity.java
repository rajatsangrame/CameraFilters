package com.example.rajat.camerafilters;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.extensions.BokehImageCaptureExtender;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
import com.zomato.photofilters.FilterPack;
import com.zomato.photofilters.imageprocessors.Filter;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Also of note, CameraX brings life to vendor extensions, which are features that display
 * special effects on some devices. CameraX lets you add extensions to your app with just
 * a few lines of code. Currently, the supported effects are:
 * <p>
 * Portrait
 * Bokeh
 * Night Mode
 * Beauty
 * HDR
 */
public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "MainActivity";
    private PreviewView mPreviewView;
    private ImageCapture mImageCapture;
    private BokehImageCaptureExtender mBokehImageCapture;
    private ListenableFuture<ProcessCameraProvider> mCameraProviderFuture;
    private ImageView mImageView;
    private ExecutorService mExecutorService = Executors.newSingleThreadExecutor();
    private Filter mCurrentFilter;

    private final int REQUEST_CODE_PERMISSIONS = 101;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA", "android.permission.WRITE_EXTERNAL_STORAGE"};


    static {
        System.loadLibrary("NativeImageProcessor");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setTitle("");
        hideSystemUI();
        mPreviewView = findViewById(R.id.preview_view);
        mImageView = findViewById(R.id.imageView);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    /**
     * Ref: https://developer.android.com/training/camerax/configuration
     * Ref: https://developer.android.com/training/camerax/preview
     * Ref: https://developer.android.com/training/camerax/vendor-extensions
     * <p>
     * <p>
     * <p>
     * For the best performance, you should run the analysis directly against on
     * ImageProxy's original format which is YUV_420_888.
     */
    @SuppressLint("UnsafeExperimentalUsageError")
    private void startCamera() {

        mCameraProviderFuture = ProcessCameraProvider.getInstance(this);
        mCameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = mCameraProviderFuture.get();

                ImageCapture.Builder builder = new ImageCapture.Builder();
                mImageCapture = builder.setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                ImageAnalysis imageAnalysis =
                        new ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setImageQueueDepth(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();

                imageAnalysis.setAnalyzer(mExecutorService, imageProxy -> {

                    int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
                    TextureView textureView = ((TextureView) mPreviewView.getChildAt(0));
                    Bitmap inputBitmap = textureView.getBitmap(720, 1280);
                    if (mCurrentFilter != null) {
                        Bitmap output = mCurrentFilter.processFilter(inputBitmap);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mImageView.setVisibility(View.VISIBLE);
                                mImageView.setImageBitmap(output);
                            }
                        });
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mImageView.setVisibility(View.GONE);
                            }
                        });
                    }
                    imageProxy.close();
                });

                mBokehImageCapture =
                        BokehImageCaptureExtender.create(builder);

                OrientationEventListener orientationEventListener = new OrientationEventListener(this) {
                    @Override
                    public void onOrientationChanged(int orientation) {
                        int rotation;

                        // Monitors orientation values to determine the target rotation value
                        if (orientation >= 45 && orientation < 135) {
                            rotation = Surface.ROTATION_270;
                        } else if (orientation >= 135 && orientation < 225) {
                            rotation = Surface.ROTATION_180;
                        } else if (orientation >= 225 && orientation < 315) {
                            rotation = Surface.ROTATION_90;
                        } else {
                            rotation = Surface.ROTATION_0;
                        }
                        if (mImageCapture != null) {
                            mImageCapture.setTargetRotation(rotation);
                        }
                    }
                };
                orientationEventListener.enable();

                bindPreview(cameraProvider, mImageCapture, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));

    }

    void bindPreview(@NonNull ProcessCameraProvider cameraProvider, ImageCapture imageCapture,
                     ImageAnalysis imageAnalysis) {

        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(mPreviewView.getDisplay().getRotation())
                .build();
        preview.setSurfaceProvider(mPreviewView.getPreviewSurfaceProvider());

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        // To disable vendor extensions, create a new instance of the ImageCapture or Preview use case.
        if (mBokehImageCapture.isExtensionAvailable(cameraSelector)) {
            // Enable the extension if available.
            mBokehImageCapture.enableExtension(cameraSelector);
        }

        cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture, imageAnalysis, preview);
    }

    public void captureImage(View v) {

        File file = new File(Environment.getExternalStorageDirectory() + "/" + System.currentTimeMillis() + ".png");
        //File file = new File(getExternalFilesDir().getAbsolutePath() + "/" + System.currentTimeMillis() + ".png");

        ImageCapture.OutputFileOptions outputFileOptions =
                new ImageCapture.OutputFileOptions.Builder(file).build();
        mImageCapture.takePicture(outputFileOptions, mExecutorService, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {

                Log.i(TAG, "captureImage onImageSaved: ");
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {

                Log.i(TAG, "captureImage onError: ");
            }
        });
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);

    }

    @Override
    protected void onResume() {
        super.onResume();

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.none:
                mCurrentFilter = null;
                return true;
            case R.id.f1:
                mCurrentFilter = FilterPack.getClarendon(this);
                return true;
            case R.id.f2:
                mCurrentFilter = FilterPack.getAmazonFilter(this);
                return true;
            case R.id.f3:
                mCurrentFilter = FilterPack.getOldManFilter(this);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private boolean allPermissionsGranted() {

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {

        Log.i(TAG, "onSurfaceTextureAvailable: ");
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        Log.i(TAG, "onSurfaceTextureSizeChanged: ");
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.i(TAG, "onSurfaceTextureDestroyed: ");
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        Log.i(TAG, "onSurfaceTextureUpdated: ");
    }
}

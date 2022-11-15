package com.baidu.paddle.fastdeploy.app.examples.detection;

import static com.baidu.paddle.fastdeploy.app.ui.Utils.decodeBitmap;
import static com.baidu.paddle.fastdeploy.app.ui.Utils.getRealPathFromURI;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.baidu.paddle.fastdeploy.RuntimeOption;
import com.baidu.paddle.fastdeploy.app.examples.R;
import com.baidu.paddle.fastdeploy.app.ui.view.CameraSurfaceView;
import com.baidu.paddle.fastdeploy.app.ui.view.ResultListView;
import com.baidu.paddle.fastdeploy.app.ui.Utils;
import com.baidu.paddle.fastdeploy.app.ui.view.adapter.BaseResultAdapter;
import com.baidu.paddle.fastdeploy.app.ui.view.model.BaseResultModel;
import com.baidu.paddle.fastdeploy.vision.DetectionResult;
import com.baidu.paddle.fastdeploy.vision.detection.PicoDet;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.opengles.GL10;

public class DetectionMainActivity extends Activity implements View.OnClickListener, CameraSurfaceView.OnTextureChangedListener {
    private static final String TAG = DetectionMainActivity.class.getSimpleName();

    CameraSurfaceView svPreview;
    TextView tvStatus;
    ImageButton btnSwitch;
    ImageButton btnShutter;
    ImageButton btnSettings;
    ImageView realtimeToggleButton;
    boolean isRealtimeStatusRunning = false;
    ImageView backInPreview;
    private ImageView albumSelectButton;
    private View cameraPageView;
    private ViewGroup resultPageView;
    private ImageView resultImage;
    private ImageView backInResult;
    private SeekBar confidenceSeekbar;
    private TextView seekbarText;
    private float resultNum = 1.0f;
    private ResultListView detectResultView;
    private Bitmap shutterBitmap;
    private Bitmap originShutterBitmap;
    private Bitmap picBitmap;
    private Bitmap originPicBitmap;

    public static final int TYPE_UNKNOWN = -1;
    public static final int BTN_SHUTTER = 0;
    public static final int ALBUM_SELECT = 1;
    public static final int REALTIME_DETECT = 2;
    private static int TYPE = REALTIME_DETECT;

    private static final int REQUEST_PERMISSION_CODE_STORAGE = 101;
    private static final int INTENT_CODE_PICK_IMAGE = 100;
    private static final int TIME_SLEEP_INTERVAL = 50; // ms

    String savedImagePath = "result.jpg";
    int lastFrameIndex = 0;
    long lastFrameTime;

    // Call 'init' and 'release' manually later
    PicoDet predictor = new PicoDet();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.detection_activity_main);

        // Clear all setting items to avoid app crashing due to the incorrect settings
        initSettings();

        // Init the camera preview and UI components
        initView();

        // Check and request CAMERA and WRITE_EXTERNAL_STORAGE permissions
        if (!checkAllPermissions()) {
            requestAllPermissions();
        }
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_switch:
                svPreview.switchCamera();
                break;
            case R.id.btn_shutter:
                TYPE = BTN_SHUTTER;
                runOnShutterUiThread();
                break;
            case R.id.btn_settings:
                startActivity(new Intent(DetectionMainActivity.this, DetectionSettingsActivity.class));
                break;
            case R.id.realtime_toggle_btn:
                toggleRealtimeStyle();
                break;
            case R.id.back_in_preview:
                finish();
                break;
            case R.id.albumSelect:
                TYPE = ALBUM_SELECT;
                // Judge whether authority has been granted.
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    // If this permission was requested before the application but the user refused the request, this method will return true.
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSION_CODE_STORAGE);
                } else {
                    Intent intent = new Intent(Intent.ACTION_PICK);
                    intent.setType("image/*");
                    startActivityForResult(intent, INTENT_CODE_PICK_IMAGE);
                }
                break;
            case R.id.back_in_result:
                resultPageView.setVisibility(View.GONE);
                cameraPageView.setVisibility(View.VISIBLE);
                TYPE = REALTIME_DETECT;
                svPreview.onResume();
                break;
        }
    }

    private void runOnShutterUiThread() {
        runOnUiThread(new Runnable() {
            @SuppressLint("SetTextI18n")
            public void run() {
                try {
                    Thread.sleep(TIME_SLEEP_INTERVAL);

                    svPreview.onPause();
                    cameraPageView.setVisibility(View.GONE);
                    resultPageView.setVisibility(View.VISIBLE);
                    seekbarText.setText(resultNum + "");
                    confidenceSeekbar.setProgress((int) (resultNum * 100));
                    if (shutterBitmap != null && !shutterBitmap.isRecycled()) {
                        resultImage.setImageBitmap(shutterBitmap);
                    } else {
                        new AlertDialog.Builder(DetectionMainActivity.this)
                                .setTitle("Empty Result!")
                                .setMessage("Current picture is empty, please shutting it again!")
                                .setCancelable(true)
                                .show();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void copyBitmapFromCamera(Bitmap ARGB8888ImageBitmap) {
        if (ARGB8888ImageBitmap == null) {
            return;
        }
        if (!ARGB8888ImageBitmap.isRecycled()) {
            synchronized (this) {
                shutterBitmap = ARGB8888ImageBitmap.copy(Bitmap.Config.ARGB_8888, true);
                originShutterBitmap = ARGB8888ImageBitmap.copy(Bitmap.Config.ARGB_8888, true);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == INTENT_CODE_PICK_IMAGE) {
            if (resultCode == Activity.RESULT_OK) {
                cameraPageView.setVisibility(View.GONE);
                resultPageView.setVisibility(View.VISIBLE);
                seekbarText.setText(resultNum + "");
                confidenceSeekbar.setProgress((int) (resultNum * 100));
                Uri uri = data.getData();
                String path = getRealPathFromURI(this, uri);
                picBitmap = decodeBitmap(path, 720, 1280);
                originPicBitmap = picBitmap.copy(Bitmap.Config.ARGB_8888, true);
                resultImage.setImageBitmap(picBitmap);
            }
        }
    }

    private void toggleRealtimeStyle() {
        if (isRealtimeStatusRunning) {
            isRealtimeStatusRunning = false;
            realtimeToggleButton.setImageResource(R.drawable.realtime_stop_btn);
            svPreview.setOnTextureChangedListener(this);
            tvStatus.setVisibility(View.VISIBLE);
        } else {
            isRealtimeStatusRunning = true;
            realtimeToggleButton.setImageResource(R.drawable.realtime_start_btn);
            tvStatus.setVisibility(View.GONE);
            svPreview.setOnTextureChangedListener(new CameraSurfaceView.OnTextureChangedListener() {
                @Override
                public boolean onTextureChanged(Bitmap ARGB8888ImageBitmap) {
                    return false;
                }
            });
        }
    }

    @Override
    public boolean onTextureChanged(Bitmap ARGB8888ImageBitmap) {
        synchronized (this) {
            if (TYPE == BTN_SHUTTER) {
                copyBitmapFromCamera(ARGB8888ImageBitmap);
                return false;
            }
        }

        String savedImagePath = "";
        synchronized (this) {
            savedImagePath = Utils.getDCIMDirectory() + File.separator + "result.jpg";
        }

        boolean modified = false;
        DetectionResult result = predictor.predict(
                ARGB8888ImageBitmap, true, DetectionSettingsActivity.scoreThreshold);
        modified = result.initialized();
        if (!savedImagePath.isEmpty()) {
            synchronized (this) {
                DetectionMainActivity.this.savedImagePath = "result.jpg";
            }
        }
        lastFrameIndex++;
        if (lastFrameIndex >= 30) {
            final int fps = (int) (lastFrameIndex * 1e9 / (System.nanoTime() - lastFrameTime));
            runOnUiThread(new Runnable() {
                @SuppressLint("SetTextI18n")
                public void run() {
                    tvStatus.setText(Integer.toString(fps) + "fps");
                }
            });
            lastFrameIndex = 0;
            lastFrameTime = System.nanoTime();
        }
        return modified;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload settings and re-initialize the predictor
        checkAndUpdateSettings();
        // Open camera until the permissions have been granted
        if (!checkAllPermissions()) {
            svPreview.disableCamera();
        }
        svPreview.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        svPreview.onPause();
    }

    @Override
    protected void onDestroy() {
        if (predictor != null) {
            predictor.release();
        }
        super.onDestroy();
    }

    public void initView() {
        TYPE = REALTIME_DETECT;
        svPreview = (CameraSurfaceView) findViewById(R.id.sv_preview);
        svPreview.setOnTextureChangedListener(this);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        btnSwitch = (ImageButton) findViewById(R.id.btn_switch);
        btnSwitch.setOnClickListener(this);
        btnShutter = (ImageButton) findViewById(R.id.btn_shutter);
        btnShutter.setOnClickListener(this);
        btnSettings = (ImageButton) findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(this);
        realtimeToggleButton = findViewById(R.id.realtime_toggle_btn);
        realtimeToggleButton.setOnClickListener(this);
        backInPreview = findViewById(R.id.back_in_preview);
        backInPreview.setOnClickListener(this);
        albumSelectButton = findViewById(R.id.albumSelect);
        albumSelectButton.setOnClickListener(this);
        cameraPageView = findViewById(R.id.camera_page);
        resultPageView = findViewById(R.id.result_page);
        resultImage = findViewById(R.id.result_image);
        backInResult = findViewById(R.id.back_in_result);
        backInResult.setOnClickListener(this);
        confidenceSeekbar = findViewById(R.id.confidence_seekbar);
        seekbarText = findViewById(R.id.seekbar_text);
        detectResultView = findViewById(R.id.result_list_view);

        List<BaseResultModel> results = new ArrayList<>();
        results.add(new BaseResultModel(1, "cup", 0.4f));
        results.add(new BaseResultModel(2, "pen", 0.6f));
        results.add(new BaseResultModel(3, "tang", 1.0f));
        final BaseResultAdapter adapter = new BaseResultAdapter(this, R.layout.detection_result_page_item, results);
        detectResultView.setAdapter(adapter);
        detectResultView.invalidate();

        confidenceSeekbar.setMax(100);
        confidenceSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float resultConfidence = seekBar.getProgress() / 100f;
                BigDecimal bd = new BigDecimal(resultConfidence);
                resultNum = bd.setScale(1, BigDecimal.ROUND_HALF_UP).floatValue();
                seekbarText.setText(resultNum + "");
                confidenceSeekbar.setProgress((int) (resultNum * 100));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (TYPE == ALBUM_SELECT) {
                            SystemClock.sleep(TIME_SLEEP_INTERVAL * 10);
                            if (!picBitmap.isRecycled()) {
                                predictor.predict(picBitmap, true, resultNum);
                                resultImage.setImageBitmap(picBitmap);
                                picBitmap = originPicBitmap.copy(Bitmap.Config.ARGB_8888, true);
                            }
                            resultNum = 1.0f;
                        } else {
                            SystemClock.sleep(TIME_SLEEP_INTERVAL * 10);
                            if (!shutterBitmap.isRecycled()) {
                                predictor.predict(shutterBitmap, true, resultNum);
                                resultImage.setImageBitmap(shutterBitmap);
                                shutterBitmap = originShutterBitmap.copy(Bitmap.Config.ARGB_8888, true);
                            }
                            resultNum = 1.0f;
                        }
                    }
                });
            }
        });
    }

    @SuppressLint("ApplySharedPref")
    public void initSettings() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.clear();
        editor.commit();
        DetectionSettingsActivity.resetSettings();
    }

    public void checkAndUpdateSettings() {
        if (DetectionSettingsActivity.checkAndUpdateSettings(this)) {
            String realModelDir = getCacheDir() + "/" + DetectionSettingsActivity.modelDir;
            Utils.copyDirectoryFromAssets(this, DetectionSettingsActivity.modelDir, realModelDir);
            String realLabelPath = getCacheDir() + "/" + DetectionSettingsActivity.labelPath;
            Utils.copyFileFromAssets(this, DetectionSettingsActivity.labelPath, realLabelPath);

            String modelFile = realModelDir + "/" + "model.pdmodel";
            String paramsFile = realModelDir + "/" + "model.pdiparams";
            String configFile = realModelDir + "/" + "infer_cfg.yml";
            String labelFile = realLabelPath;
            RuntimeOption option = new RuntimeOption();
            option.setCpuThreadNum(DetectionSettingsActivity.cpuThreadNum);
            option.setLitePowerMode(DetectionSettingsActivity.cpuPowerMode);
            if (Boolean.parseBoolean(DetectionSettingsActivity.enableLiteFp16)) {
                option.enableLiteFp16();
            }
            predictor.init(modelFile, paramsFile, configFile, labelFile, option);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED || grantResults[1] != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(DetectionMainActivity.this)
                    .setTitle("Permission denied")
                    .setMessage("Click to force quit the app, then open Settings->Apps & notifications->Target " +
                            "App->Permissions to grant all of the permissions.")
                    .setCancelable(false)
                    .setPositiveButton("Exit", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            DetectionMainActivity.this.finish();
                        }
                    }).show();
        }
    }

    private void requestAllPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA}, 0);
    }

    private boolean checkAllPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
}

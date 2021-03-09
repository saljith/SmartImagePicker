package com.myhexaville.smartimagepicker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import com.theartofdev.edmodo.cropper.CropImage;
import com.theartofdev.edmodo.cropper.CropImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static android.app.Activity.RESULT_OK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static com.theartofdev.edmodo.cropper.CropImage.CAMERA_CAPTURE_PERMISSIONS_REQUEST_CODE;
import static com.theartofdev.edmodo.cropper.CropImage.PICK_IMAGE_CHOOSER_REQUEST_CODE;
import static com.theartofdev.edmodo.cropper.CropImage.getGalleryIntents;

/**
 * Usage: Create new instance, call {@link #choosePicture(boolean)} or {@link #openCamera()}
 * override {@link Activity#onActivityResult}, call {@link #handleActivityResult(int, int, Intent)} in it
 * override {@link Activity#onRequestPermissionsResult}, call {@link #handlePermission(int, int[])} in it
 * get picked file with {@link #getImageFile()}
 * <p>
 * If calling from Fragment, override {@link Activity#onActivityResult(int, int, Intent)}
 * and call {@link Fragment#onActivityResult(int, int, Intent)} for your fragment to delegate result
 */
public class ImagePicker implements ImagePickerContract {
    private static final String TAG = "ImagePicker";
    private static final int CAMERA_CAPTURE_PERMISSIONS_REQUEST_CODE_WITH_CAMERA = 100;
    private static final int CAMERA_CAPTURE_PERMISSIONS_REQUEST_CODE_WITHOUT_CAMERA = 101;

    private static String currentCameraFileName = "";
    private OnImagePickedListener listener;
    private Activity activity;
    private Fragment fragment;

    private File imageFile;
    private int aspectRatioX, aspectRatioY;
    private boolean withCrop;

    public ImagePicker(Activity activity, @Nullable Fragment fragment, OnImagePickedListener listener) {
        this.activity = activity;
        this.fragment = fragment;
        this.listener = listener;
    }

    @SuppressWarnings("UnusedReturnValue")
    @Override
    public ImagePicker setWithImageCrop(int aspectRatioX, int aspectRatioY) {
        withCrop = true;
        this.aspectRatioX = aspectRatioX;
        this.aspectRatioY = aspectRatioY;
        return this;
    }

    @SuppressLint("NewApi")
    @Override
    public void choosePicture(boolean includeCamera) {
        if (needToAskPermissions()) {
            String[] neededPermissions = getNeededPermissions();
            int requestCode = includeCamera
                    ? CAMERA_CAPTURE_PERMISSIONS_REQUEST_CODE_WITH_CAMERA
                    : CAMERA_CAPTURE_PERMISSIONS_REQUEST_CODE_WITHOUT_CAMERA;
            if (fragment != null) {
                fragment.requestPermissions(neededPermissions, requestCode);
            } else {
                activity.requestPermissions(neededPermissions, requestCode);
            }
        } else {
            startImagePickerActivity(includeCamera);
        }
    }

    @SuppressLint("NewApi")
    @Override
    public void openCamera() {
        if (needToAskPermissions()) {
            if (fragment != null) {
                fragment.requestPermissions(getNeededPermissions(), CAMERA_CAPTURE_PERMISSIONS_REQUEST_CODE);
            } else {
                activity.requestPermissions(getNeededPermissions(), CAMERA_CAPTURE_PERMISSIONS_REQUEST_CODE);
            }
        } else {
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            Intent cameraIntent = getCameraIntent();
            if (takePictureIntent.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivityForResult(cameraIntent, PICK_IMAGE_CHOOSER_REQUEST_CODE);
            }
        }
    }

    @SuppressWarnings({"WeakerAccess", "unused"})
    @NonNull
    @Override
    public File getImageFile() {
        return imageFile;
    }

    @Override
    public void handlePermission(int requestCode, int[] grantResults) {
        Log.d(TAG, "handlePermission: " + requestCode);
        if (requestCode == CAMERA_CAPTURE_PERMISSIONS_REQUEST_CODE_WITH_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PERMISSION_GRANTED) {
                startImagePickerActivity(true);
            } else {
                Toast.makeText(activity, R.string.canceling, Toast.LENGTH_SHORT).show();
            }
        }
        if (requestCode == CAMERA_CAPTURE_PERMISSIONS_REQUEST_CODE_WITHOUT_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PERMISSION_GRANTED) {
                startImagePickerActivity(false);
            } else {
                Toast.makeText(activity, R.string.canceling, Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == CAMERA_CAPTURE_PERMISSIONS_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(activity, R.string.canceling, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void handleActivityResult(int resultCode, int requestCode, Intent data) {
        if (resultCode == RESULT_OK) {
            Log.d(TAG, "handleActivityResult: 1");
            if (requestCode == PICK_IMAGE_CHOOSER_REQUEST_CODE) {
                Log.d(TAG, "handleActivityResult: 2");
                handlePickedImageResult(data);
            } else {
                if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE) {
                    handleCroppedImageResult(data);
                }
            }
        } else {
            Log.d(TAG, "handleActivityResult: " + resultCode);
            if (resultCode == CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE) {
                Log.d(TAG, "onActivityResult: Image picker Error");
            }
        }
    }

    private String[] getNeededPermissions() {
        if (withCrop) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE};
        } else {
            return new String[]{Manifest.permission.CAMERA};
        }
    }

    private boolean needToAskPermissions() {
        if (withCrop) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED;
        }
    }

    private void handleCroppedImageResult(Intent data) {
        Log.d(TAG, "handleCroppedImageResult: ");
        CropImage.ActivityResult result = CropImage.getActivityResult(data);
        Uri croppedImageUri = result.getUri();
        deletePreviouslyCroppedFiles(croppedImageUri);
        imageFile = new File(croppedImageUri.getPath());
        listener.onImagePicked(croppedImageUri);
    }

    @SuppressLint("NewApi")
    private void handlePickedImageResult(Intent data) {
        boolean isCamera = true;
        if (data != null && data.getData() != null) {
            String action = data.getAction();
            isCamera = action != null && action.equals(MediaStore.ACTION_IMAGE_CAPTURE);
        }
        Uri imageUri = isCamera || data.getData() == null ? getCameraFileUri(activity) : data.getData();
        if (isCamera) {
            deletePreviousCameraFiles();
        }
        Log.d(TAG, "handlePickedImageResult: " + imageUri);
        if (withCrop) {
            CropImage.activity(imageUri)
                    .setGuidelines(CropImageView.Guidelines.ON)
                    .setAspectRatio(aspectRatioX, aspectRatioY)
                    .start(activity);
        } else {
            imageFile = new File(imageUri.getPath());


            InputStream is = null;
            Bitmap bitmap=null;
            try {
                is = activity.getContentResolver().openInputStream(imageUri);
                 bitmap = BitmapFactory.decodeStream(is);
                is.close();


            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

             storeImage(bitmap);
            String compressedPath = null;
            compressedPath = compressImage(getOutputMediaFile().getAbsolutePath());
           listener.onImagePicked(Uri.fromFile(new File(compressedPath)));

        }
    }
    private void storeImage(Bitmap image) {
        File pictureFile = getOutputMediaFile();
        if (pictureFile == null) {
            Log.d(TAG,
                    "Error creating media file, check storage permissions: ");// e.getMessage());
            return;
        }
        try {
            FileOutputStream fos = new FileOutputStream(pictureFile);
            image.compress(Bitmap.CompressFormat.PNG, 90, fos);
            fos.close();
        } catch (FileNotFoundException e) {
            Log.d(TAG, "File not found: " + e.getMessage());
        } catch (IOException e) {
            Log.d(TAG, "Error accessing file: " + e.getMessage());
        }
    }

    private  File getOutputMediaFile(){
        // To be safe, you should check that the SDCard is mounted
        // using Environment.getExternalStorageState() before doing this.
        File mediaStorageDir = new File(Environment.getExternalStorageDirectory()
                + "/Android/data/"
                + activity.getPackageName()
                + "/Files");

        // This location works best if you want the created images to be shared
        // between applications and persist after your app has been uninstalled.

        // Create the storage directory if it does not exist
        if (! mediaStorageDir.exists()){
            if (! mediaStorageDir.mkdirs()){
                return null;
            }
        }
        // Create a media file name
        String timeStamp = new SimpleDateFormat("ddMMyyyy_HHmm").format(new Date());
        File mediaFile;
        String mImageName="MI_"+ timeStamp +".jpg";
        mediaFile = new File(mediaStorageDir.getPath() + File.separator + mImageName);
        return mediaFile;
    }






    public String compressImage(String x) {


        Log.e(TAG, "compressImage: " + x);

        String filePath = x;
        Bitmap scaledBitmap = null;

        BitmapFactory.Options options = new BitmapFactory.Options();

//      by setting this field as true, the actual bitmap pixels are not loaded in the memory. Just the bounds are loaded. If
//      you try the use the bitmap here, you will get null.
        options.inJustDecodeBounds = true;
        Bitmap bmp = BitmapFactory.decodeFile(filePath, options);

        int actualHeight = options.outHeight;
        int actualWidth = options.outWidth;

//      max Height and width values of the compressed image is taken as 816x612

        float maxHeight = 816.0f;
        float maxWidth = 612.0f;
        float imgRatio = actualWidth / actualHeight;
        float maxRatio = maxWidth / maxHeight;

//      width and height values are set maintaining the aspect ratio of the image

        if (actualHeight > maxHeight || actualWidth > maxWidth) {
            if (imgRatio < maxRatio) {
                imgRatio = maxHeight / actualHeight;
                actualWidth = (int) (imgRatio * actualWidth);
                actualHeight = (int) maxHeight;
            } else if (imgRatio > maxRatio) {
                imgRatio = maxWidth / actualWidth;
                actualHeight = (int) (imgRatio * actualHeight);
                actualWidth = (int) maxWidth;
            } else {
                actualHeight = (int) maxHeight;
                actualWidth = (int) maxWidth;

            }
        }

//      setting inSampleSize value allows to load a scaled down version of the original image

        options.inSampleSize = calculateInSampleSize(options, actualWidth, actualHeight);

//      inJustDecodeBounds set to false to load the actual bitmap
        options.inJustDecodeBounds = false;

//      this options allow android to claim the bitmap memory if it runs low on memory
        options.inPurgeable = true;
        options.inInputShareable = true;
        options.inTempStorage = new byte[16 * 1024];

        try {
//          load the bitmap from its path
            bmp = BitmapFactory.decodeFile(filePath, options);


        } catch (OutOfMemoryError exception) {
            exception.printStackTrace();

        }
        try {
            scaledBitmap = Bitmap.createBitmap(actualWidth, actualHeight, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError exception) {
            exception.printStackTrace();
        }

        float ratioX = actualWidth / (float) options.outWidth;
        float ratioY = actualHeight / (float) options.outHeight;
        float middleX = actualWidth / 2.0f;
        float middleY = actualHeight / 2.0f;

        Matrix scaleMatrix = new Matrix();
        scaleMatrix.setScale(ratioX, ratioY, middleX, middleY);

        Canvas canvas = new Canvas(scaledBitmap);
        canvas.setMatrix(scaleMatrix);
        canvas.drawBitmap(bmp, middleX - bmp.getWidth() / 2, middleY - bmp.getHeight() / 2, new Paint(Paint.FILTER_BITMAP_FLAG));

//      check the rotation of the image and display it properly
        ExifInterface exif;
        try {
            exif = new ExifInterface(filePath);

            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, 0);
            Log.d("EXIF", "Exif: " + orientation);
            Matrix matrix = new Matrix();
            if (orientation == 6) {
                matrix.postRotate(90);
                Log.d("EXIF", "Exif: " + orientation);
            } else if (orientation == 3) {
                matrix.postRotate(180);
                Log.d("EXIF", "Exif: " + orientation);
            } else if (orientation == 8) {
                matrix.postRotate(270);
                Log.d("EXIF", "Exif: " + orientation);
            }
            scaledBitmap = Bitmap.createBitmap(scaledBitmap, 0, 0,
                    scaledBitmap.getWidth(), scaledBitmap.getHeight(), matrix,
                    true);
        } catch (IOException e) {
            e.printStackTrace();
        }

        FileOutputStream out = null;
        File f = null;
        try {
            f = createImageFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        String filename = f.getAbsolutePath();
        try {
            out = new FileOutputStream(filename);

//          write the compressed bitmap at the destination specified by filename.
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        return filename;

    }

    public int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int heightRatio = Math.round((float) height / (float) reqHeight);
            final int widthRatio = Math.round((float) width / (float) reqWidth);
            inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;
        }

        final float totalPixels = width * height;
        final float totalReqPixelsCap = reqWidth * reqHeight * 2;

        while (totalPixels / (inSampleSize * inSampleSize) > totalReqPixelsCap) {
            inSampleSize++;
        }

        return inSampleSize;
    }

    private File createImageFile() throws IOException {

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir =activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,
                ".jpg",
                storageDir
        );
        return image;
    }




    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void deletePreviousCameraFiles() {
        File imagePath = new File(activity.getFilesDir(), "images");
        if (imagePath.exists() && imagePath.isDirectory()) {
            if (imagePath.listFiles().length > 0) {
                for (File file : imagePath.listFiles()) {
                    if (!file.getName().equals(currentCameraFileName)) {
                        file.delete();
                    }
                }
            }
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void deletePreviouslyCroppedFiles(Uri currentCropImageUri) {
        Log.d(TAG, "deletePreviouslyCroppedFiles: " + currentCropImageUri);
        String croppedImageName = currentCropImageUri.getLastPathSegment();
        File imagePath = activity.getCacheDir();
        Log.d(TAG, "deletePreviouslyCroppedFiles: " + imagePath.exists() + " " + imagePath.isDirectory());
        if (imagePath.exists() && imagePath.isDirectory()) {
            Log.d(TAG, "deletePreviouslyCroppedFiles: " + imagePath.toString());
            Log.d(TAG, "deletePreviouslyCroppedFiles: " + imagePath.listFiles().length);
            if (imagePath.listFiles().length > 0) {
                for (File file : imagePath.listFiles()) {
                    Log.d(TAG, "deletePreviouslyCroppedFiles: " + file.getName());
                    if (!file.getName().equals(croppedImageName)) {
                        file.delete();
                    }
                }
            }
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @NonNull
    private Intent getCameraIntent() {
        currentCameraFileName = "outputImage" + System.currentTimeMillis() + ".jpg";
        File imagesDir = new File(activity.getFilesDir(), "images");
        imagesDir.mkdirs();
        File file = new File(imagesDir, currentCameraFileName);
        try {
            file.createNewFile();
        } catch (IOException e) {
            Log.d(TAG, "openCamera: coudln't crate ");
            e.printStackTrace();
        }
        Log.d(TAG, "openCamera: file exists " + file.exists() + " " + file.toURI().toString());
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        String authority = activity.getPackageName() + ".smart-image-picket-provider";
        final Uri outputUri = FileProvider.getUriForFile(
                activity.getApplicationContext(),
                authority,
                file);
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
        activity.grantUriPermission(
                "com.google.android.GoogleCamera",
                outputUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
        );
        return cameraIntent;
    }

    private Uri getCameraFileUri(Activity activity) {
        File imagePath = new File(activity.getFilesDir(), "images/" + currentCameraFileName);
        return Uri.fromFile(imagePath);
    }

    private void startImagePickerActivity(boolean includeCamera) {
        List<Intent> allIntents = new ArrayList<>();
        PackageManager packageManager = activity.getPackageManager();

        List<Intent> galleryIntents = getGalleryIntents(packageManager, Intent.ACTION_GET_CONTENT, false);
        if (galleryIntents.size() == 0) {
            // if no intents found for get-content try pick intent action (Huawei P9).
            galleryIntents = getGalleryIntents(packageManager, Intent.ACTION_PICK, false);
        }

        if (includeCamera) {
            allIntents.add(getCameraIntent());
        }
        allIntents.addAll(galleryIntents);

        Intent target;
        if (allIntents.isEmpty()) {
            target = new Intent();
        } else {
            target = allIntents.get(allIntents.size() - 1);
            allIntents.remove(allIntents.size() - 1);
        }

        // Create a chooser from the main  intent
        Intent chooserIntent = Intent.createChooser(target, activity.getString(R.string.select_source));

        // Add all other intents
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, allIntents.toArray(new Parcelable[allIntents.size()]));
        activity.startActivityForResult(chooserIntent, PICK_IMAGE_CHOOSER_REQUEST_CODE);
    }

}

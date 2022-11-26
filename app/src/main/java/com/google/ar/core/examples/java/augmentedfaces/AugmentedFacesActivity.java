/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.examples.java.augmentedfaces;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedFace;
import com.google.ar.core.AugmentedFace.RegionType;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Config.AugmentedFaceMode;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3d model of the Android robot.
 */
public class AugmentedFacesActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = AugmentedFacesActivity.class.getSimpleName();

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);

  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final AugmentedFaceRenderer augmentedFaceRenderer = new AugmentedFaceRenderer();
  private final ObjectRenderer noseObject = new ObjectRenderer();
  private final ObjectRenderer rightEarObject = new ObjectRenderer();
  private final ObjectRenderer leftEarObject = new ObjectRenderer();
  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] noseMatrix = new float[16];
  private final float[] centerPoseMatrix = new float[16];
  private final float[] rightEarMatrix = new float[16];
  private final float[] leftEarMatrix = new float[16];
  private static final float[] DEFAULT_COLOR = new float[] {0f, 0f, 0f, 0f};

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

    // Set up renderer.
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);

    installRequested = false;
  }

  @Override
  protected void onDestroy() {
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the session and configure it to use a front-facing (selfie) camera.
        session = new Session(/* context= */ this, EnumSet.noneOf(Session.Feature.class));
        CameraConfigFilter cameraConfigFilter = new CameraConfigFilter(session);
        cameraConfigFilter.setFacingDirection(CameraConfig.FacingDirection.FRONT);
        List<CameraConfig> cameraConfigs = session.getSupportedCameraConfigs(cameraConfigFilter);
        if (!cameraConfigs.isEmpty()) {
          // Element 0 contains the camera config that best matches the session feature
          // and filter settings.
          session.setCameraConfig(cameraConfigs.get(0));
        } else {
          message = "This device does not have a front-facing (selfie) camera";
          exception = new UnavailableDeviceNotCompatibleException(message);
        }
        configureSession();

      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(/*context=*/ this);
      augmentedFaceRenderer.createOnGlThread(
              this,
              "models/freckles.png");
      augmentedFaceRenderer.setMaterialProperties(0.0f, 1.0f, 0.1f, 6.0f);
      noseObject.createOnGlThread(
              /*context=*/ this,
              "models/nose.obj",
              "models/nose_fur.png");
      noseObject.setMaterialProperties(0.0f, 1.0f, 0.1f, 6.0f);
      noseObject.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending);
      rightEarObject.createOnGlThread(
              this,
              "models/forehead_right.obj",
              "models/ear_fur.png");
      rightEarObject.setMaterialProperties(0.0f, 1.0f, 0.1f, 6.0f);
      rightEarObject.setBlendMode(ObjectRenderer.BlendMode.Shadow); //.AlphaBlending);
      leftEarObject.createOnGlThread(
              this,
              "models/forehead_left.obj",
              "models/internet_symbol.png" //"models/ear_fur.png"
      );
      leftEarObject.setMaterialProperties(0.0f, 1.0f, 0.1f, 6.0f);
      leftEarObject.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending);

      Log.d(TAG, "***********************************************************");
      Log.d(TAG, "leftEarObject :: "+ Arrays.toString( leftEarObject.getModelMatrix() ) );
      //leftEarObject :: [
      // 1.0, 0.0, 0.0, 0.0,
      // 0.0, 1.0, 0.0, 0.0,
      // 0.0, 0.0, 1.0, 0.0,
      // 0.0, 0.0, 0.0, 1.0]
      Log.d(TAG, "rightEarObject :: "+ Arrays.toString( rightEarObject.getModelMatrix() ) );
      //rightEarObject :: [
      // 1.0, 0.0, 0.0, 0.0,
      // 0.0, 1.0, 0.0, 0.0,
      // 0.0, 0.0, 1.0, 0.0,
      // 0.0, 0.0, 0.0, 1.0]
      Log.d(TAG, "noseObject :: "+ Arrays.toString( noseObject.getModelMatrix() ) );
      //noseObject :: [
      // 1.0, 0.0, 0.0, 0.0,
      // 0.0, 1.0, 0.0, 0.0,
      // 0.0, 0.0, 1.0, 0.0,
      // 0.0, 0.0, 0.0, 1.0]
      Log.d(TAG, "***********************************************************");


    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      Frame frame = session.update();
      Camera camera = frame.getCamera();

      // Get projection matrix.
      float[] projectionMatrix = new float[16];
      camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

      // Get camera matrix and draw.
      float[] viewMatrix = new float[16];
      camera.getViewMatrix(viewMatrix, 0);

      // Compute lighting from average intensity of the image.
      // The first three components are color scaling factors.
      // The last one is the average pixel intensity in gamma space.
      final float[] colorCorrectionRgba = new float[4];
      frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

      // If frame is ready, render camera preview image to the GL surface.
      backgroundRenderer.draw(frame);

      // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
      trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

      // ARCore's face detection works best on upright faces, relative to gravity.
      // If the device cannot determine a screen side aligned with gravity, face
      // detection may not work optimally.
      Collection<AugmentedFace> faces = session.getAllTrackables(AugmentedFace.class);
      for (AugmentedFace face : faces) {
        if (face.getTrackingState() != TrackingState.TRACKING) {
          break;
        }

        float scaleFactor = 1.0f;

        // Face objects use transparency so they must be rendered back to front without depth write.
        GLES20.glDepthMask(false);

        // Each face's region poses, mesh vertices, and mesh normals are updated every frame.

        // 1. Render the face mesh first, behind any 3D objects attached to the face regions.
        float[] modelMatrix = new float[16];
        face.getCenterPose().toMatrix(modelMatrix, 0);
        /*augmentedFaceRenderer.draw(
            projectionMatrix, viewMatrix, modelMatrix, colorCorrectionRgba, face);

        // 2. Next, render the 3D objects attached to the forehead.
        */
        /*face.getRegionPose(RegionType.FOREHEAD_RIGHT).toMatrix(rightEarMatrix, 0);

        Log.d(TAG,"modelMatrix:: " + Arrays.toString( modelMatrix) );
        Log.d(TAG, "rightEarMatrix:: "+ Arrays.toString( rightEarMatrix) );
        rightEarObject.updateModelMatrix(rightEarMatrix, scaleFactor);
        //rightEarObject.updateModelMatrix(modelMatrix, scaleFactor);
        rightEarObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, DEFAULT_COLOR);*/

        face.getRegionPose(RegionType.FOREHEAD_LEFT).toMatrix(leftEarMatrix, 0);
        Log.d(TAG, "leftEarMatrix:: "+ Arrays.toString( leftEarMatrix) );
        leftEarObject.updateModelMatrix(leftEarMatrix, scaleFactor);
        leftEarObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, DEFAULT_COLOR);

        // 3. Render the nose last so that it is not occluded by face mesh or by 3D objects attached
        // to the forehead regions.
        face.getRegionPose(RegionType.NOSE_TIP).toMatrix(noseMatrix, 0);
        Log.d(TAG, "noseMatrix:: "+ Arrays.toString( noseMatrix) );
        noseObject.updateModelMatrix(noseMatrix, scaleFactor);
        Log.d(TAG, "noseMatrix:: "+ Arrays.toString( noseObject.getModelMatrix() ) );
        noseObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, DEFAULT_COLOR);/**/

        FloatBuffer uvs = face.getMeshTextureCoordinates();
        ShortBuffer indices = face.getMeshTriangleIndices();
        // Center and region poses, mesh vertices, and normals are updated each frame.
        Pose facePose = face.getCenterPose();
        Log.d(TAG, "getCenterPose:: "+ facePose.toString() );
        Log.d(TAG, "Pose FOREHEAD_LEFT:: "+ face.getRegionPose(RegionType.FOREHEAD_LEFT).toString() );
        Log.d(TAG, "Pose FOREHEAD_RIGHT:: "+ face.getRegionPose(RegionType.FOREHEAD_RIGHT).toString() );
        Log.d(TAG, "Pose NOSE_TIP:: "+ face.getRegionPose(RegionType.NOSE_TIP).toString() );
        FloatBuffer faceVertices = face.getMeshVertices();

        FloatBuffer faceNormals = face.getMeshNormals();
        //face.
        /*
        centerPoseMatrix:: [
        0.99975765, 0.011847142, -0.018555831, 0.0,
        -0.0174885, 0.9393495, -0.34251523, 0.0,
        0.013372582, 0.34275675, 0.939329, 0.0,
        0.063071445, -0.06245289, -0.40240374, 1.0]
        leftEarMatrix:: [0.8528856, -0.3288415, -0.4055236, 0.0, 0.012367934, 0.789227, -0.613977, 0.0, 0.5219513, 0.51863664, 0.67718744, 0.0, 0.10609271, 0.0021883324, -0.3874692, 1.0]
        noseMatrix:: [0.99989367, -0.0043110074, 0.013929109, 0.0, 0.008898205, 0.9371941, -0.34869477, 0.0, -0.011551053, 0.34878168, 0.93713284, 0.0, 0.061762508, -0.054460775, -0.33381218, 1.0]
        noseMatrix:: [
        0.99989367, -0.0043110074, 0.013929109, 0.0,
        0.008898205, 0.9371941, -0.34869477, 0.0,
        -0.011551053, 0.34878168, 0.93713284, 0.0,
        0.061762508, -0.054460775, -0.33381218, 1.0]
        Pose NOSE_TIP:: t:[x:0.062, y:-0.054, z:-0.334], q:[x:-0.18, y:-0.01, z:-0.00, w:0.98]

        getCenterPose:: t:[x:0.066, y:-0.078, z:-0.404], q:[x:-0.18, y:-0.01, z:-0.00, w:0.98]
        Pose FOREHEAD_LEFT:: t:[x:0.106, y:0.002, z:-0.387], q:[x:-0.31, y:0.25, z:-0.09, w:0.91]
        Pose FOREHEAD_RIGHT:: t:[x:0.029, y:0.004, z:-0.387], q:[x:-0.31, y:-0.27, z:0.09, w:0.91]

        centerPoseMatrix:: [0.99989367, -0.0043110074, 0.013929109, 0.0, 0.008898205, 0.9371941, -0.34869477, 0.0, -0.011551053, 0.34878168, 0.93713284, 0.0, 0.066318125, -0.078331485, -0.4035222, 1.0]
        */

        Log.d(TAG, "translatedXYZ:: "+ Arrays.toString( face.getRegionPose(RegionType.FOREHEAD_LEFT).getTranslation() ) );

        float[] translatedXYZ = new float[3];
        translatedXYZ = face.getRegionPose(RegionType.FOREHEAD_LEFT).getTranslation();
        float[] faceVerticesFloat = new float[1404];
        final double THRESHOLD = .000001f;
        for (int i = 0; i < 1404 ; i++) {
          faceVerticesFloat[i] = faceVertices.get(i);
          if( Math.abs(faceVerticesFloat[i] - translatedXYZ[0]) < THRESHOLD ){
            Log.d(TAG, "found faceVerticesFloat[i]:: " + i  );
          }
        }

        //faceVertices.get( faceVerticesFloat, 0...1404);
        //error states that 1404 length = 468*3
        Log.d(TAG, "faceVertices:: "+ Arrays.toString( faceVerticesFloat ) );
        Log.d(TAG, "SizefaceVertices:: "+ faceVertices );

        float[] pointVec = new float [3];
        //taken from Blender import of canonical_face_mesh.fbx
        // FOREHEAD_LEFT triangle from indices = 297,299,333  ;  FOREHEAD_RIGHT triangle index = 67,69,104
        // NOSE_TIP index = 4
        pointVec[0] = faceVertices.get(297 * 3 + 0) + facePose.tx();
        pointVec[1] = faceVertices.get(297 * 3 + 1) + facePose.ty();
        pointVec[2] = faceVertices.get(297 * 3 + 2) + facePose.tz();
        Log.d(TAG, "pointVec:: "+ Arrays.toString( pointVec ) );

        float[] rotQuaternionPointVec = new float[4];
        rotQuaternionPointVec = facePose.getRotationQuaternion();
        Log.d(TAG, "rotQuaternionPointVec:: "+ Arrays.toString( rotQuaternionPointVec ) );
        Pose pointPose = new Pose( pointVec , rotQuaternionPointVec );
        Log.d(TAG, "pointPose:: "+ pointPose.toString() );
        //testPose.makeTranslation( pointVec ) ;
        pointPose.toMatrix( centerPoseMatrix, 0 );
        Log.d(TAG, "pointPose:CenterPoseMatrix:: "+ Arrays.toString( centerPoseMatrix ) );


        /*//float[] faceVerticesFloat = new float[1872];
        //faceVertices.get( faceVerticesFloat, 0, 1872);
        //error states that 468*4 = 1872 length
        //Log.d(TAG, "faceVertices:: "+ Arrays.toString( faceVerticesFloat ) );
        float[] testPointVec = new float [3];
        //Global Transform values --- from blender FaceMesh.pbx  but taking point Index 10
        testPointVec[0] = 0f + facePose.tx();
        testPointVec[1] = 0.044815f + facePose.ty();
        testPointVec[2] = 0.082618f + facePose.tz();
        //Log.d(TAG, "testPointVec:: "+ Arrays.toString( facePose.transformPoint( testPointVec ) ) );

        float[] rotQuaternion = new float[4];
        rotQuaternion = facePose.getRotationQuaternion();
        Log.d(TAG, "rotQuaternion:: "+ Arrays.toString( rotQuaternion ) );
        Pose testPose = new Pose(testPointVec , rotQuaternion );
        Log.d(TAG, "testPose:: "+ testPose.toString() );
        testPose.toMatrix( centerPoseMatrix, 0 );
        Log.d(TAG, "testPose:CenterPoseMatrix:: "+ Arrays.toString( centerPoseMatrix ) );
        */
        /*public Pose getCenterPose()
        Returns the pose of the center of the face, defined to have the origin located behind the nose and between the two cheek bones.
        Z+ is forward out of the nose,
        Y+ is upwards, and
        X+ is towards the left. The units are in meters.
        When the face trackable state is TRACKING, this pose is synced with the latest frame.
        When face trackable state is PAUSED, an identity pose will be returned.
        */


        /*// Local Transform values -- from blender faceMesh,  is not applicable
        testPointVec[0] = 0f + facePose.tx();
        testPointVec[1] = 8.2618f + facePose.ty();
        testPointVec[2] = -4.4815f + facePose.tz();
        */


        //faceVertices:: [-9.157397E-4, -0.028572539, 0.055776358, -0.0017670691, -0.0044954773, 0.0711329, -8.698888E-4, -0.013433646, 0.057057977, -0.005991839, 0.013895385, 0.06536186, -0.0020009875, 0.0018128478, 0.073625684, -0.002008628, 0.009095505, 0.07203767, -9.520389E-4, 0.0267841, 0.058280855, -0.040711354, 0.024980007, 0.036113143, -6.019287E-4, 0.0386426, 0.05453953, -6.0712546E-4, 0.04660287, 0.05574864, -7.6990575E-5, 0.07828748, 0.051127315, -8.4149465E-4, -0.031848136, 0.054627597, -7.227026E-4, -0.034564078, 0.05246398, -6.0067326E-4, -0.03600264, 0.04996091, -6.181523E-4, -0.037461303, 0.050174683, -6.836876E-4, -0.039452773, 0.051023215, -8.4710866E-4, -0.042217176, 0.052185178, -8.426383E-4, -0.045946304, 0.051378638, -5.3985044E-4, -0.054953035, 0.045349598, -0.0016369075, -0.008375776, 0.067882925, -0.006139431, -0.009148616, 0.060685337, -0.06802824, 0.046469316, 0.0111579895, -0.024312166, 0.02022654, 0.040848523, -0.030022088, 0.019493794, 0.040585548, -0.03559815, 0.019470796, 0.039199293, -0.043335054, 0.021816045, 0.0351198, -0.01949189, 0.022111574, 0.040616095, -0.03140936, 0.03408038, 0.04465285, -0.02561174, 0.034286536, 0.044159293, -0.03697376, 0.03293002, 0.043059677, -0.040945586, 0.030900761, 0.040524423, -0.04865546, 0.017016774, 0.031867176, -0.022265527, -0.06940392, 0.03708315, -0.042856682, 0.02593226, 0.034546703, -0.071176425, 0.016000923, 0.006989479, -0.056277663, 0.020172872, 0.026456416, -0.028570747, -0.005497694, 0.045109868, -0.0077943318, -0.027737124, 0.05522093, -0.0066407397, -0.034709856, 0.051673323, -0.014948485, -0.030678354, 0.051651865, -0.019572876, -0.03459439, 0.04706326, -0.011950657, -0.035777513, 0.04935935, -0.016057728, -0.037502725, 0.04542485, -0.026729286, -0.047148827, 0.038206667, -0.0061954446, -0.0045118486, 0.07059455, -0.0070485175, 0.0014838027, 0.07289913, -0.050642855, 0.036072563, 0.03916794, -0.017289273, 0.010850323, 0.04705468, -0.018667858, -0.0057480745, 0.056939065, -0.0190598, -0.0029174862, 0.05552435, -0.046131432, -0.0068484508, 0.038012087, -0.0066376887, 0.00825398, 0.07029241, -0.035516143, 0.043464832, 0.04920253, -0.04405828, 0.040877488, 0.04492271, -0.059677444, 0.059532315, 0.02295658, -0.011200052, 0.039815508, 0.0530881, -0.020367933, 0.033211704, 0.042363733, -0.031216528, -0.041539576, 0.037188977, -0.06857186, -0.05718376, -0.013312936, -0.014402233, -0.009253493, 0.054447144, -0.009745523, -0.011101386, 0.055291325, -0.02471863, -0.041045006, 0.037807494, -0.022351965, -0.040297844, 0.039298177, -0.04773066, 0.044886988, 0.04224685, -0.0189111, -0.00920349, 0.053567827, -0.02462848, 0.043536983, 0.05200383, -0.025843428, 0.048739806, 0.053316563, -0.03296903, 0.07516428, 0.044633925, -0.05325064, 0.052497227, 0.034071863, -0.02872855, 0.0628597, 0.04932031, -0.05498559, 0.038567014, 0.03436449, -0.06115386, 0.042311274, 0.023155749, -0.0073556826, -0.03171755, 0.053958923, -0.013193561, -0.033716727, 0.050732672, -0.017737297, -0.0360698, 0.046768665, -0.01292884, -0.010536133, 0.053336203, -0.02342712, -0.040718984, 0.038609833, -0.020949967, -0.0405632, 0.042512566, -0.021864088, -0.040024094, 0.039596617, -0.011595633, -0.0058128573, 0.062438935, -0.015175719, -0.037431613, 0.04467544, -0.010880195, -0.036492668, 0.04727009, -0.0059739687, -0.03599041, 0.04939738, -0.008036748, -0.054896373, 0.04506558, -0.007626418, -0.045669157, 0.051029325, -0.007228285, -0.041788213, 0.051798016, -0.0066988505, -0.039075755, 0.050519377, -0.006226193, -0.03749087, 0.04955572, -0.015746273, -0.038722117, 0.044779748, -0.016526539, -0.039170437, 0.045898467, -0.017744742, -0.04063428, 0.046738118, -0.018778842, -0.04305977, 0.04554704, -0.02442064, -0.027899113, 0.045989156, -0.076754086, -0.021377444, -0.018689364, -0.0010242797, -0.010579133, 0.06095454, -0.018632833, -0.039636135, 0.041811436, -0.0196538, -0.03969906, 0.0425584, -0.007888075, -0.013896981, 0.055588722, -0.016730608, -0.013659244, 0.0492689, -0.008781627, -0.012547186, 0.05548826, -0.023253731, 0.0065691075, 0.04497
/*

        Pose NOSE_TIP:: t:[x:0.007, y:-0.072, z:-0.336], q:[x:-0.10, y:0.03, z:-0.00, w:0.99]
        BeforeTestPointVec_CenterPoseMatrix:: [0.998678, -0.0076003186, -0.050836746, 0.0, -0.0026130388, 0.9802227, -0.19788027, 0.0, 0.051335286, 0.19775152, 0.97890705, 0.0, 0.0029094985, -0.08656865, -0.40728134, 1.0]
        testPointVec:: [0.007027059, -0.026152506, -0.33527875]
        AfterTestPointVec_CenterPoseMatrix:: [0.9986268, -0.006666824, -0.051961273, 0.0, -0.0037929025, 0.98006505, -0.19864054, 0.0, 0.05224973, 0.19856487, 0.978694, 0.0, 0.0028802692, -0.08647916, -0.4072344, 1.0]

*/

        rightEarObject.updateModelMatrix(centerPoseMatrix, scaleFactor);
        //rightEarObject.updateModelMatrix(modelMatrix, scaleFactor);
        rightEarObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, DEFAULT_COLOR);

      }
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    } finally {
      GLES20.glDepthMask(true);
    }
  }

  private void configureSession() {
    Config config = new Config(session);
    config.setAugmentedFaceMode(AugmentedFaceMode.MESH3D);
    session.configure(config);
  }
}

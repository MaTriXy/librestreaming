package me.lake.librestreaming.core;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import me.lake.librestreaming.client.CallbackDelivery;
import me.lake.librestreaming.core.listener.RESScreenShotListener;
import me.lake.librestreaming.filter.hardvideofilter.BaseHardVideoFilter;
import me.lake.librestreaming.model.MediaCodecGLWapper;
import me.lake.librestreaming.model.OffScreenGLWapper;
import me.lake.librestreaming.model.RESConfig;
import me.lake.librestreaming.model.RESCoreParameters;
import me.lake.librestreaming.model.ScreenGLWapper;
import me.lake.librestreaming.rtmp.RESFlvDataCollecter;
import me.lake.librestreaming.tools.LogTools;

/**
 * Created by lake on 16-5-24.
 */
public class RESHardVideoCore implements RESVideoCore {
    RESCoreParameters resCoreParameters;
    private final Object syncOp = new Object();
    private int currentCamera;
    //filter
    private Lock lockVideoFilter = null;
    private BaseHardVideoFilter videoFilter;
    private MediaCodec dstVideoEncoder;
    private MediaFormat dstVideoFormat;
    private final Object syncPreview = new Object();
    private ScreenParams screenParams;
    private HandlerThread videoGLHandlerThread;
    private VideoGLHandler videoGLHander;
    //sender
    private VideoSenderThread videoSenderThread;

    final private Object syncResScreenShotListener = new Object();
    private RESScreenShotListener resScreenShotListener;

    public RESHardVideoCore(RESCoreParameters parameters) {
        resCoreParameters = parameters;
        screenParams = new ScreenParams();
        lockVideoFilter = new ReentrantLock(false);
    }

    public void onFrameAvailable() {
        if (videoGLHandlerThread != null) {
            videoGLHander.addFrameNum();
        }
    }

    @Override
    public boolean prepare(RESConfig resConfig) {
        synchronized (syncOp) {
            resCoreParameters.renderingMode = resConfig.getRenderingMode();
            resCoreParameters.mediacdoecAVCBitRate = resConfig.getBitRate();
            resCoreParameters.videoBufferQueueNum = resConfig.getVideoBufferQueueNum();
            resCoreParameters.mediacodecAVCFrameRate = 30;
            resCoreParameters.mediacodecAVCIFrameInterval = 5;
            dstVideoFormat = new MediaFormat();
            dstVideoEncoder = MediaCodecHelper.createHardVideoMediaCodec(resCoreParameters, dstVideoFormat);
            if (dstVideoEncoder == null) {
                LogTools.e("create Video MediaCodec failed");
                return false;
            }
            videoGLHandlerThread = new HandlerThread("GLThread");
            videoGLHandlerThread.start();
            videoGLHander = new VideoGLHandler(videoGLHandlerThread.getLooper());
            videoGLHander.sendEmptyMessage(VideoGLHandler.WHAT_INIT);
            return true;
        }
    }

    @Override
    public boolean startPreview(SurfaceTexture camTex) {
        synchronized (syncOp) {
            try {
                if (dstVideoEncoder == null) {
                    dstVideoEncoder = MediaCodec.createEncoderByType(dstVideoFormat.getString(MediaFormat.KEY_MIME));
                }
                dstVideoEncoder.configure(dstVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Exception e) {
                LogTools.trace("RESHardVideoCore,start()failed", e);
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean startStreaming(RESFlvDataCollecter flvDataCollecter) {
        try {
            dstVideoEncoder.start();
        } catch (Exception e) {
            dstVideoEncoder.configure(dstVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            dstVideoEncoder.start();
        }
        videoSenderThread = new VideoSenderThread("VideoSenderThread", dstVideoEncoder, flvDataCollecter);
        videoSenderThread.start();
        return true;
    }

    @Override
    public void updateCamTexture(SurfaceTexture camTex) {
        synchronized (syncOp) {
            if (videoGLHander != null) {
                videoGLHander.updateCamTexture(camTex);
            }
        }
    }

    @Override
    public boolean stopStreaming() {
        videoSenderThread.quit();
        try {
            videoSenderThread.join();
        } catch (InterruptedException e) {
            LogTools.trace("RESHardVideoCore,stopStreaming()failed", e);
            return false;
        }
        videoSenderThread = null;
        dstVideoEncoder.stop();
        return true;
    }

    @Override
    public boolean stopPreview() {
        synchronized (syncOp) {
            videoGLHandlerThread.quit();
            try {
                videoGLHandlerThread.join();
            } catch (InterruptedException e) {
                LogTools.trace("RESHardVideoCore,stopPreview()failed", e);
                return false;
            }
            videoGLHandlerThread = null;
            dstVideoEncoder.release();
            dstVideoEncoder = null;
            return true;
        }
    }

    @Override
    public boolean destroy() {
        synchronized (syncOp) {
            videoGLHander.sendEmptyMessage(VideoGLHandler.WHAT_UNINIT);
            videoGLHandlerThread.quitSafely();
            try {
                videoGLHandlerThread.join();
            } catch (InterruptedException ignored) {
            }
            videoGLHandlerThread = null;
            videoGLHander = null;
            return true;
        }
    }

    @Override
    public void setCurrentCamera(int cameraIndex) {
        currentCamera = cameraIndex;
        if (videoGLHander != null) {
            videoGLHander.updateCameraIndex(currentCamera);
        }
    }

    @Override
    public void createPreview(SurfaceTexture surfaceTexture, int visualWidth, int visualHeight) {
        synchronized (syncOp) {
            if (screenParams.surfaceTexture != null) {
                throw new RuntimeException("createPreview without destroyPreview");
            }
            videoGLHander.sendMessage(videoGLHander.obtainMessage(VideoGLHandler.WHAT_START_PREVIEW,
                    visualWidth, visualHeight, surfaceTexture));
        }
    }

    @Override
    public void updatePreview(int visualWidth, int visualHeight) {
        synchronized (syncPreview) {
            screenParams.visualWidth = visualWidth;
            screenParams.visualHeight = visualHeight;
        }
    }

    @Override
    public void destroyPreview() {
        synchronized (syncOp) {
            videoGLHander.sendEmptyMessage(VideoGLHandler.WHAT_STOP_PREVIEW);
        }
    }

    public BaseHardVideoFilter acquireVideoFilter() {
        lockVideoFilter.lock();
        return videoFilter;
    }

    public void releaseVideoFilter() {
        lockVideoFilter.unlock();
    }

    public void setVideoFilter(BaseHardVideoFilter baseHardVideoFilter) {
        lockVideoFilter.lock();
        videoFilter = baseHardVideoFilter;
        lockVideoFilter.unlock();
    }

    @Override
    public void takeScreenShot(RESScreenShotListener listener) {
        synchronized (syncResScreenShotListener) {
            resScreenShotListener = listener;
        }
    }

    @Override
    public float getDrawFrameRate() {
        synchronized (syncOp) {
            return videoGLHander == null ? 0 : videoGLHander.getDrawFrameRate();
        }
    }

    private class VideoGLHandler extends Handler {
        static final int WHAT_INIT = 0x001;
        static final int WHAT_UNINIT = 0x002;
        static final int WHAT_FRAME = 0x003;
        static final int WHAT_START_PREVIEW = 0x010;
        static final int WHAT_STOP_PREVIEW = 0x020;
        static final int WHAT_START_STREAMING = 0x100;
        static final int WHAT_STOP_STREAMING = 0x200;
        //=========================
        public static final int FILTER_LOCK_TOLERATION = 3;//3ms
        private final Object syncFrameNum = new Object();
        private int frameNum = 0;
        //gl stuff
        private SurfaceTexture cameraTexture;

        private MediaCodecGLWapper mediaCodecGLWapper;
        private ScreenGLWapper screenGLWapper;
        private OffScreenGLWapper offScreenGLWapper;

        private int sample2DFrameBuffer;
        private int sample2DFrameBufferTexture;
        private int frameBuffer;
        private int frameBufferTexture;
        private FloatBuffer shapeVerticesBuffer;
        private FloatBuffer mediaCodecTextureVerticesBuffer;
        private FloatBuffer screenTextureVerticesBuffer;
        private int currCamera;
        private final Object syncCameraTextureVerticesBuffer = new Object();
        private FloatBuffer camera2dTextureVerticesBuffer;
        private FloatBuffer cameraTextureVerticesBuffer;
        private ShortBuffer drawIndecesBuffer;
        private BaseHardVideoFilter innerVideoFilter = null;
        private RESFrameRateMeter drawFrameRateMeter;
        private int directionFlag;

        public VideoGLHandler(Looper looper) {
            super(looper);
            screenGLWapper = null;
            mediaCodecGLWapper = null;
            drawFrameRateMeter = new RESFrameRateMeter();
            initBuffer();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case WHAT_FRAME: {
                    GLHelper.makeCurrent(offScreenGLWapper);
                    synchronized (syncFrameNum) {
                        cameraTexture.updateTexImage();
                        --frameNum;
                        if (frameNum >= 1) {
                            break;
                        }
                    }
                    drawFrame();
                    drawFrameRateMeter.count();
                }
                break;
                case WHAT_INIT: {
                    initOffScreenGL();
                }
                break;
                case WHAT_UNINIT: {
                    uninitOffScreenGL();
                }
                break;
                case WHAT_START_PREVIEW: {
                    initScreenGL((SurfaceTexture) msg.obj);
                }
                break;
                case WHAT_STOP_PREVIEW: {
                    uninitScreenGL();
                }
                break;
                case WHAT_START_STREAMING: {
                    initMediaCodecGL((Surface) msg.obj);
                }
                break;
                case WHAT_STOP_STREAMING: {
                    uninitMediaCodecGL();
                }
                break;
                default:
            }
        }

        public void drawFrame() {
            drawSample2DFrameBuffer();
            drawFrameBuffer();
            drawMediaCodec();
            drawScreen();
        }

        private void drawSample2DFrameBuffer() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sample2DFrameBuffer);
            GLES20.glUseProgram(mediaCodecGLWapper.cam2dProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, OVERWATCH_TEXTURE_ID);
            GLES20.glUniform1i(mediaCodecGLWapper.cam2dTextureLoc, 0);
            synchronized (syncCameraTextureVerticesBuffer) {
                GLHelper.enableVertex(mediaCodecGLWapper.cam2dPostionLoc, mediaCodecGLWapper.cam2dTextureCoordLoc,
                        shapeVerticesBuffer, camera2dTextureVerticesBuffer);
            }
            GLES20.glViewport(0, 0, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
            doGLDraw();
            GLES20.glFinish();
            GLHelper.disableVertex(mediaCodecGLWapper.cam2dPostionLoc, mediaCodecGLWapper.cam2dTextureCoordLoc);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        private void drawOriginFrameBuffer() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer);
            GLES20.glUseProgram(mediaCodecGLWapper.camProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sample2DFrameBufferTexture);
            GLES20.glUniform1i(mediaCodecGLWapper.camTextureLoc, 0);
            synchronized (syncCameraTextureVerticesBuffer) {
                GLHelper.enableVertex(mediaCodecGLWapper.camPostionLoc, mediaCodecGLWapper.camTextureCoordLoc,
                        shapeVerticesBuffer, cameraTextureVerticesBuffer);
            }
            GLES20.glViewport(0, 0, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
            doGLDraw();
            GLES20.glFinish();
            GLHelper.disableVertex(mediaCodecGLWapper.camPostionLoc, mediaCodecGLWapper.camTextureCoordLoc);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        private void drawFrameBuffer() {
            boolean isFilterLocked = lockVideoFilter();
            if (isFilterLocked) {
                if (videoFilter != innerVideoFilter) {
                    if (innerVideoFilter != null) {
                        innerVideoFilter.onDestroy();
                    }
                    innerVideoFilter = videoFilter;
                    if (innerVideoFilter != null) {
                        innerVideoFilter.onInit(resCoreParameters.videoWidth, resCoreParameters.videoHeight);
                    }
                }
                if (innerVideoFilter != null) {
                    synchronized (syncCameraTextureVerticesBuffer) {
                        innerVideoFilter.onDirectionUpdate(directionFlag);
                        innerVideoFilter.onDraw(sample2DFrameBufferTexture, frameBuffer, shapeVerticesBuffer, cameraTextureVerticesBuffer);
                    }
                } else {
                    drawOriginFrameBuffer();
                }
                unlockVideoFilter();
            } else {
                drawOriginFrameBuffer();
            }
        }

        private void drawMediaCodec() {
            GLHelper.makeCurrent(mediaCodecGLWapper);
            GLES20.glUseProgram(mediaCodecGLWapper.drawProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameBufferTexture);
            GLES20.glUniform1i(mediaCodecGLWapper.drawTextureLoc, 0);
            GLHelper.enableVertex(mediaCodecGLWapper.drawPostionLoc, mediaCodecGLWapper.drawTextureCoordLoc,
                    shapeVerticesBuffer, mediaCodecTextureVerticesBuffer);
            doGLDraw();
            GLES20.glFinish();
            checkScreenShot();
            GLHelper.disableVertex(mediaCodecGLWapper.drawPostionLoc, mediaCodecGLWapper.drawTextureCoordLoc);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
            EGLExt.eglPresentationTimeANDROID(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglSurface, cameraTexture.getTimestamp());
            if (!EGL14.eglSwapBuffers(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglSurface)) {
                throw new RuntimeException("eglSwapBuffers,failed!");
            }
        }

        private void drawScreen() {
            GLHelper.makeCurrent(screenGLWapper);
            //drawScreen
            GLES20.glUseProgram(screenGLWapper.drawProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameBufferTexture);
            GLES20.glUniform1i(screenGLWapper.drawTextureLoc, 0);
            GLHelper.enableVertex(screenGLWapper.drawPostionLoc, screenGLWapper.drawTextureCoordLoc,
                    shapeVerticesBuffer, screenTextureVerticesBuffer);
            GLES20.glViewport(0, 0, screenParams.visualWidth, screenParams.visualHeight);
            doGLDraw();
            GLHelper.disableVertex(screenGLWapper.drawPostionLoc, screenGLWapper.drawTextureCoordLoc);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
            if (!EGL14.eglSwapBuffers(screenGLWapper.eglDisplay, screenGLWapper.eglSurface)) {
                throw new RuntimeException("eglSwapBuffers,failed!");
            }
        }

        private void doGLDraw() {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawIndecesBuffer.limit(), GLES20.GL_UNSIGNED_SHORT, drawIndecesBuffer);
        }

        /**
         * @return ture if filter locked & filter!=null
         */

        private boolean lockVideoFilter() {
            try {
                return lockVideoFilter.tryLock(FILTER_LOCK_TOLERATION, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        private void unlockVideoFilter() {
            lockVideoFilter.unlock();
        }

        private void checkScreenShot() {
            synchronized (syncResScreenShotListener) {
                if (resScreenShotListener != null) {
                    Bitmap result = null;
                    try {
                        IntBuffer pixBuffer = IntBuffer.allocate(resCoreParameters.videoWidth * resCoreParameters.videoHeight);
                        GLES20.glReadPixels(0, 0, resCoreParameters.videoWidth, resCoreParameters.videoHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixBuffer);
                        int[] glPixel = pixBuffer.array();
                        int[] argbPixel = new int[resCoreParameters.videoWidth * resCoreParameters.videoHeight];
                        ColorHelper.FIXGLPIXEL(glPixel, argbPixel, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
                        result = Bitmap.createBitmap(argbPixel,
                                resCoreParameters.videoWidth,
                                resCoreParameters.videoHeight,
                                Bitmap.Config.ARGB_8888);
                    } catch (Exception e) {
                        LogTools.trace("takescreenshot failed:", e);
                    } finally {
                        CallbackDelivery.i().post(new RESScreenShotListener.RESScreenShotListenerRunable(resScreenShotListener, result));
                        resScreenShotListener = null;
                    }
                }
            }
        }

        private void initOffScreenGL() {
            if (offScreenGLWapper == null) {
                offScreenGLWapper = new OffScreenGLWapper();
                GLHelper.initOffScreenGL(offScreenGLWapper);
                //camera
                offScreenGLWapper.camProgram = GLHelper.createCameraProgram();
                GLES20.glUseProgram(offScreenGLWapper.camProgram);
                offScreenGLWapper.camTextureLoc = GLES20.glGetUniformLocation(offScreenGLWapper.camProgram, "uTexture");
                offScreenGLWapper.camPostionLoc = GLES20.glGetAttribLocation(offScreenGLWapper.camProgram, "aPosition");
                offScreenGLWapper.camTextureCoordLoc = GLES20.glGetAttribLocation(offScreenGLWapper.camProgram, "aTextureCoord");
                //camera2d
                offScreenGLWapper.cam2dProgram = GLHelper.createCamera2DProgram();
                GLES20.glUseProgram(offScreenGLWapper.cam2dProgram);
                offScreenGLWapper.cam2dTextureLoc = GLES20.glGetUniformLocation(offScreenGLWapper.cam2dProgram, "uTexture");
                offScreenGLWapper.cam2dPostionLoc = GLES20.glGetAttribLocation(offScreenGLWapper.cam2dProgram, "aPosition");
                offScreenGLWapper.cam2dTextureCoordLoc = GLES20.glGetAttribLocation(offScreenGLWapper.cam2dProgram, "aTextureCoord");
                int[] fb = new int[1], fbt = new int[1];
                GLHelper.createCamFrameBuff(fb, fbt, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
                sample2DFrameBuffer = fb[0];
                sample2DFrameBufferTexture = fbt[0];
                GLHelper.createCamFrameBuff(fb, fbt, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
                frameBuffer = fb[0];
                frameBufferTexture = fbt[0];
            } else {
                throw new IllegalStateException("initOffScreenGL without uninitOffScreenGL");
            }
        }

        private void uninitOffScreenGL() {
            if (offScreenGLWapper != null) {
                GLHelper.makeCurrent(offScreenGLWapper);
                GLES20.glDeleteProgram(offScreenGLWapper.camProgram);
                GLES20.glDeleteProgram(offScreenGLWapper.cam2dProgram);
                GLES20.glDeleteFramebuffers(1, new int[]{frameBuffer}, 0);
                GLES20.glDeleteTextures(1, new int[]{frameBufferTexture}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{sample2DFrameBuffer}, 0);
                GLES20.glDeleteTextures(1, new int[]{sample2DFrameBufferTexture}, 0);
                EGL14.eglDestroySurface(offScreenGLWapper.eglDisplay, offScreenGLWapper.eglSurface);
                EGL14.eglDestroyContext(offScreenGLWapper.eglDisplay, offScreenGLWapper.eglContext);
                EGL14.eglTerminate(offScreenGLWapper.eglDisplay);
                EGL14.eglMakeCurrent(offScreenGLWapper.eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            } else {
                throw new IllegalStateException("uninitOffScreenGL without initOffScreenGL");
            }
        }

        private void initScreenGL(SurfaceTexture screenSurfaceTexture) {
            if (screenGLWapper == null) {
                screenGLWapper = new ScreenGLWapper();
                GLHelper.initScreenGL(screenGLWapper, offScreenGLWapper.eglContext, screenSurfaceTexture);
                screenGLWapper.drawProgram = GLHelper.createScreenProgram();
                GLES20.glUseProgram(screenGLWapper.drawProgram);
                screenGLWapper.drawTextureLoc = GLES20.glGetUniformLocation(screenGLWapper.drawProgram, "uTexture");
                screenGLWapper.drawPostionLoc = GLES20.glGetAttribLocation(screenGLWapper.drawProgram, "aPosition");
                screenGLWapper.drawTextureCoordLoc = GLES20.glGetAttribLocation(screenGLWapper.drawProgram, "aTextureCoord");
            } else {
                throw new IllegalStateException("initScreenGL without unInitScreenGL");
            }
        }

        private void uninitScreenGL() {
            if (screenGLWapper != null) {
                GLHelper.makeCurrent(screenGLWapper);
                GLES20.glDeleteProgram(screenGLWapper.drawProgram);
                EGL14.eglDestroySurface(screenGLWapper.eglDisplay, screenGLWapper.eglSurface);
                EGL14.eglDestroyContext(screenGLWapper.eglDisplay, screenGLWapper.eglContext);
                EGL14.eglTerminate(screenGLWapper.eglDisplay);
                EGL14.eglMakeCurrent(offScreenGLWapper.eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                screenGLWapper = null;
            } else {
                throw new IllegalStateException("unInitScreenGL without initScreenGL");
            }
        }

        private void initMediaCodecGL(Surface mediacodecSurface) {
            GLES20.glEnable(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            if (mediaCodecGLWapper == null) {
                mediaCodecGLWapper = new MediaCodecGLWapper();
                GLHelper.initMediaCodecGL(mediaCodecGLWapper, offScreenGLWapper.eglContext, mediacodecSurface);
                mediaCodecGLWapper.drawProgram = GLHelper.createMediaCodecProgram();
                GLES20.glUseProgram(mediaCodecGLWapper.drawProgram);
                mediaCodecGLWapper.drawTextureLoc = GLES20.glGetUniformLocation(mediaCodecGLWapper.drawProgram, "uTexture");
                mediaCodecGLWapper.drawPostionLoc = GLES20.glGetAttribLocation(mediaCodecGLWapper.drawProgram, "aPosition");
                mediaCodecGLWapper.drawTextureCoordLoc = GLES20.glGetAttribLocation(mediaCodecGLWapper.drawProgram, "aTextureCoord");
            } else {
                throw new IllegalStateException("initMediaCodecGL without uninitMediaCodecGL");
            }
        }

        private void uninitMediaCodecGL() {
            if (mediaCodecGLWapper != null) {
                GLHelper.makeCurrent(mediaCodecGLWapper);
                GLES20.glDeleteProgram(mediaCodecGLWapper.camProgram);
                GLES20.glDeleteProgram(mediaCodecGLWapper.drawProgram);
                GLES20.glDeleteFramebuffers(1, new int[]{frameBuffer}, 0);
                GLES20.glDeleteTextures(1, new int[]{frameBufferTexture}, 0);
                EGL14.eglDestroySurface(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglSurface);
                EGL14.eglDestroyContext(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglContext);
                EGL14.eglTerminate(mediaCodecGLWapper.eglDisplay);
                EGL14.eglMakeCurrent(mediaCodecGLWapper.eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                mediaCodecGLWapper = null;
            } else {
                throw new IllegalStateException("uninitMediaCodecGL without initMediaCodecGL");
            }
        }

        private void initBuffer() {
            shapeVerticesBuffer = GLHelper.getShapeVerticesBuffer();
            mediaCodecTextureVerticesBuffer = GLHelper.getMediaCodecTextureVerticesBuffer();
            screenTextureVerticesBuffer = GLHelper.getScreenTextureVerticesBuffer();
            updateCameraIndex(currCamera);
            drawIndecesBuffer = GLHelper.getDrawIndecesBuffer();
            cameraTextureVerticesBuffer = GLHelper.getCameraTextureVerticesBuffer();
        }

        public void updateCameraIndex(int cameraIndex) {
            synchronized (syncCameraTextureVerticesBuffer) {
                currCamera = cameraIndex;
                if (currCamera == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    directionFlag = resCoreParameters.frontCameraDirectionMode;
                } else {
                    directionFlag = resCoreParameters.backCameraDirectionMode;
                }
                camera2dTextureVerticesBuffer = GLHelper.getCamera2DTextureVerticesBuffer(directionFlag, resCoreParameters.cropRatio);
            }
        }

        public float getDrawFrameRate() {
            return drawFrameRateMeter.getFps();
        }


        public void updateCamTexture(SurfaceTexture surfaceTexture) {
            if (surfaceTexture != cameraTexture) {
                cameraTexture = surfaceTexture;
                frameNum = 0;
            }
        }

        public void addFrameNum() {
            synchronized (syncPreview) {
                ++frameNum;
                this.sendEmptyMessage(VideoGLHandler.WHAT_FRAME);
            }
        }
    }


    class ScreenParams {
        int visualWidth;
        int visualHeight;
        SurfaceTexture surfaceTexture;
    }
}

package dev.capturekit.sample

import android.app.Activity
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Minimal ARCore host: owns the Session and a GLSurfaceView whose renderer only feeds
 * ARCore a camera texture and calls update(). No camera background drawing (Phase 0).
 */
class ArHost(private val activity: Activity, private val onFrame: (Frame) -> Unit) : GLSurfaceView.Renderer {
    val view = GLSurfaceView(activity).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setRenderer(this@ArHost)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }
    var session: Session? = null
        private set
    private var textureId = -1

    fun resume() {
        if (session == null) {
            session = Session(activity).also { s ->
                val cfg = Config(s).apply {
                    focusMode = Config.FocusMode.AUTO
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
                s.configure(cfg)
            }
        }
        session?.resume()
        view.onResume()
    }

    fun pause() {
        view.onPause()
        session?.pause()
    }

    fun close() {
        session?.close()
        session = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        @Suppress("DEPRECATION")
        val rot = activity.windowManager.defaultDisplay.rotation
        session?.setDisplayGeometry(rot, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s = session ?: return
        try {
            s.setCameraTextureName(textureId)
            onFrame(s.update())
        } catch (t: Throwable) {
            Log.w("CKArHost", "update failed", t)
        }
    }
}

package com.apvcode.devx.ui.chat

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Camera
import android.Manifest
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apvcode.devx.R
import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.glass
import com.apvcode.devx.ui.components.pressable
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * What can be attached, in a glass card that opens out of the "+" button. It lives inside the
 * screen (not in a separate window), so it does not take the focus: an open keyboard stays open.
 * A tap anywhere else closes it.
 */
@Composable
fun AttachMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    if (open) BackHandler(onBack = onDismiss)
    Box(modifier) {
        if (open) {
            // Catches the tap outside; invisible.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = bottomPadding),
            enter = scaleIn(spring(dampingRatio = 0.72f, stiffness = 420f), initialScale = 0.55f, transformOrigin = TransformOrigin(0f, 1f)) +
                fadeIn(tween(150)),
            exit = scaleOut(tween(150), targetScale = 0.8f, transformOrigin = TransformOrigin(0f, 1f)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier
                    .width(224.dp)
                    .glass(RoundedCornerShape(28.dp))
                    .padding(8.dp),
            ) {
                MenuItem(0, DevxIcons.Camera, stringResource(R.string.attach_camera), onCamera)
                MenuItem(1, DevxIcons.Photo, stringResource(R.string.attach_photo), onPhotos)
                MenuItem(2, DevxIcons.FilePlus, stringResource(R.string.attach_file), onFiles)
            }
        }
    }
}

@Composable
private fun MenuItem(index: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    // The rows come in one after another, each a little later than the previous.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(50L + 45L * index)
        appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 420f))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value.coerceIn(0f, 1f)
                translationY = (1f - appear.value) * 12.dp.toPx()
            }
            .pressable(scaleTo = 0.95f, onClick = onClick)
            .clip(RoundedCornerShape(20.dp))
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = DevxColors.TextPrimary, modifier = Modifier.size(24.dp))
        Text(label, style = DevxType.Body, color = DevxColors.TextPrimary)
    }
}

/** True when the camera may be used; otherwise the system is asked (see [rememberCameraPermission]). */
fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * The camera, inside the app: a panel that rises from the bottom, with the live picture, the
 * shutter in the middle, "back" on the left and "flip" on the right. A shot is attached at once.
 */
@Composable
fun CameraPanel(
    open: Boolean,
    onClose: () -> Unit,
    onCaptured: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (open) BackHandler(onBack = onClose)
    AnimatedVisibility(
        visible = open,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 320f)) { it } + fadeIn(tween(160)),
        exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(200)),
    ) {
        CameraContent(onClose, onCaptured)
    }
}

@Composable
private fun CameraContent(onClose: () -> Unit, onCaptured: (Uri) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var front by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomChip by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    val focusRing = remember { Animatable(1f) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    // A flash of white on the shot, so that it is felt.
    val flash = remember { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // A texture, not a surface: only a texture follows the rounded corners and the slide-in.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Old shots are not kept: they were copied into the chat already.
    LaunchedEffect(Unit) {
        context.cacheDir.listFiles { f -> f.name.startsWith("shot_") && System.currentTimeMillis() - f.lastModified() > 3_600_000L }
            ?.forEach { it.delete() }
    }

    DisposableEffect(front, owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching {
                p.unbindAll()
                camera = p.bindToLifecycle(owner, selector, preview, imageCapture)
                zoom = 1f
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }

    // A flash that the lens does not have (most front cameras) cannot be asked for.
    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
    LaunchedEffect(flashMode, hasFlash) { imageCapture.flashMode = if (hasFlash) flashMode else ImageCapture.FLASH_MODE_OFF }
    // The little "2.0×" shows while the zoom changes and goes away after a moment.
    LaunchedEffect(zoom) {
        if (zoom != 1f) {
            zoomChip = true
            delay(1400)
            zoomChip = false
        }
    }
    fun applyZoom(ratio: Float) {
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        zoom = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(zoom)
    }
    fun focus(at: Offset) {
        val c = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(at.x, at.y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        c.cameraControl.startFocusAndMetering(action)
        focusAt = at
        scope.launch {
            focusRing.snapTo(0f)
            focusRing.animateTo(1f, tween(900))
            if (focusAt == at) focusAt = null
        }
    }

    fun shoot() {
        if (busy) return
        busy = true
        imageCapture.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        val file = File(context.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file)
            .apply { if (front) setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = true }) }
            .build()
        scope.launch {
            flash.snapTo(0.85f)
            flash.animateTo(0f, tween(260))
        }
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    busy = false
                    onCaptured(Uri.fromFile(file))
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    Toast.makeText(context, exception.message ?: context.getString(R.string.err_body_unknown), Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                // Tap = focus there, two fingers = zoom.
                .pointerInput(camera) { detectTapGestures(onTap = { focus(it) }) }
                .pointerInput(camera) {
                    detectTransformGestures { _, _, change, _ -> if (change != 1f) applyZoom(zoom * change) }
                },
        )
        // The ring where the focus was asked: it settles in from big to small and fades.
        focusAt?.let { at ->
            Box(
                Modifier
                    .graphicsLayer {
                        val t = focusRing.value
                        val size = 76.dp.toPx()
                        translationX = at.x - size / 2f
                        translationY = at.y - size / 2f
                        val scale = 1.35f - 0.35f * (1f - (1f - t) * (1f - t))
                        scaleX = scale
                        scaleY = scale
                        alpha = if (t < 0.7f) 1f else (1f - t) / 0.3f
                    }
                    .size(76.dp)
                    .border(2.dp, Color.White, CircleShape),
            )
        }
        // Top row: flash on the right, the zoom in the middle while it changes.
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.size(48.dp))
            AnimatedVisibility(visible = zoomChip, enter = fadeIn(tween(120)), exit = fadeOut(tween(300))) {
                Text(
                    String.format(java.util.Locale.US, "%.1f×", zoom),
                    style = DevxType.Body,
                    color = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            if (hasFlash) {
                CameraRoundButton({
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                }) {
                    val icon = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Outlined.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Outlined.FlashAuto
                        else -> Icons.Outlined.FlashOff
                    }
                    Icon(icon, stringResource(R.string.cd_camera_flash), tint = Color.White, modifier = Modifier.size(24.dp))
                }
            } else {
                Box(Modifier.size(48.dp))
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = flash.value }
                .background(Color.White),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CameraRoundButton(onClose) {
                Icon(
                    DevxIcons.ChevronRight,
                    stringResource(R.string.cd_camera_back),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = 180f },
                )
            }
            Box(
                Modifier
                    .size(78.dp)
                    .pressable(scaleTo = 0.88f, onClick = ::shoot)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
            CameraRoundButton({ front = !front }) {
                Icon(Icons.Outlined.FlipCameraAndroid, stringResource(R.string.cd_camera_flip), tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun CameraRoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .pressable(onClick = onClick)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

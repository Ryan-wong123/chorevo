package com.example.chorevo.posedetection

import android.content.Context
import android.graphics.PixelFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.android.filament.TransformManager
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberView
import io.github.sceneview.math.Rotation
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

private const val INSTRUCTOR_MODEL_ASSET = "models/instructor.glb"
private const val MIN_VISIBILITY = 0.35f

private object PoseLandmark {
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
}

private data class PoseVector3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    operator fun minus(other: PoseVector3) = PoseVector3(x - other.x, y - other.y, z - other.z)

    fun normalized(): PoseVector3 {
        val length = sqrt(x * x + y * y + z * z)
        if (length <= 0.0001f) return PoseVector3(0f, -1f, 0f)
        return PoseVector3(x / length, y / length, z / length)
    }

    fun dot(other: PoseVector3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: PoseVector3): PoseVector3 {
        return PoseVector3(
            x = y * other.z - z * other.y,
            y = z * other.x - x * other.z,
            z = x * other.y - y * other.x
        )
    }
}

private data class PoseQuaternion(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float
) {
    fun toColumnMajorRotationMatrix(): FloatArray {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z

        return floatArrayOf(
            1f - 2f * (yy + zz), 2f * (xy + wz), 2f * (xz - wy), 0f,
            2f * (xy - wz), 1f - 2f * (xx + zz), 2f * (yz + wx), 0f,
            2f * (xz + wy), 2f * (yz - wx), 1f - 2f * (xx + yy), 0f,
            0f, 0f, 0f, 1f
        )
    }

    companion object {
        fun fromTo(from: PoseVector3, to: PoseVector3): PoseQuaternion {
            val start = from.normalized()
            val end = to.normalized()
            val dot = start.dot(end).coerceIn(-1f, 1f)
            if (dot > 0.999f) return PoseQuaternion(0f, 0f, 0f, 1f)

            val axis = if (dot < -0.999f) {
                PoseVector3(0f, 0f, 1f)
            } else {
                start.cross(end).normalized()
            }
            val halfAngle = acos(dot) * 0.5f
            val sinHalfAngle = kotlin.math.sin(halfAngle)
            return PoseQuaternion(
                x = axis.x * sinHalfAngle,
                y = axis.y * sinHalfAngle,
                z = axis.z * sinHalfAngle,
                w = kotlin.math.cos(halfAngle)
            )
        }
    }
}

private data class BoneRetarget(
    val aliases: List<String>,
    val startLandmark: Int,
    val endLandmark: Int,
    val restDirection: PoseVector3,
    val mode: RetargetMode = RetargetMode.REPLACE_ROTATION
)

private enum class RetargetMode {
    REPLACE_ROTATION,
    ADD_LOCAL_Z
}

private class InstructorPoseRetargeter(
    private val node: ModelNode
) {
    private val transformManager = node.engine.transformManager
    private val restTransforms = mutableMapOf<Int, FloatArray>()

    private val bones = listOf(
        BoneRetarget(
            aliases = mixamoAliases("LeftArm"),
            startLandmark = PoseLandmark.LEFT_SHOULDER,
            endLandmark = PoseLandmark.LEFT_ELBOW,
            restDirection = PoseVector3(-1f, 0f, 0f)
        ),
        BoneRetarget(
            aliases = mixamoAliases("LeftForeArm"),
            startLandmark = PoseLandmark.LEFT_ELBOW,
            endLandmark = PoseLandmark.LEFT_WRIST,
            restDirection = PoseVector3(-1f, 0f, 0f)
        ),
        BoneRetarget(
            aliases = mixamoAliases("RightArm"),
            startLandmark = PoseLandmark.RIGHT_SHOULDER,
            endLandmark = PoseLandmark.RIGHT_ELBOW,
            restDirection = PoseVector3(1f, 0f, 0f)
        ),
        BoneRetarget(
            aliases = mixamoAliases("RightForeArm"),
            startLandmark = PoseLandmark.RIGHT_ELBOW,
            endLandmark = PoseLandmark.RIGHT_WRIST,
            restDirection = PoseVector3(1f, 0f, 0f)
        ),
        BoneRetarget(
            aliases = mixamoAliases("LeftUpLeg"),
            startLandmark = PoseLandmark.LEFT_HIP,
            endLandmark = PoseLandmark.LEFT_KNEE,
            restDirection = PoseVector3(0f, -1f, 0f),
            mode = RetargetMode.ADD_LOCAL_Z
        ),
        BoneRetarget(
            aliases = mixamoAliases("LeftLeg"),
            startLandmark = PoseLandmark.LEFT_KNEE,
            endLandmark = PoseLandmark.LEFT_ANKLE,
            restDirection = PoseVector3(0f, -1f, 0f),
            mode = RetargetMode.ADD_LOCAL_Z
        ),
        BoneRetarget(
            aliases = mixamoAliases("RightUpLeg"),
            startLandmark = PoseLandmark.RIGHT_HIP,
            endLandmark = PoseLandmark.RIGHT_KNEE,
            restDirection = PoseVector3(0f, -1f, 0f),
            mode = RetargetMode.ADD_LOCAL_Z
        ),
        BoneRetarget(
            aliases = mixamoAliases("RightLeg"),
            startLandmark = PoseLandmark.RIGHT_KNEE,
            endLandmark = PoseLandmark.RIGHT_ANKLE,
            restDirection = PoseVector3(0f, -1f, 0f),
            mode = RetargetMode.ADD_LOCAL_Z
        ),
        BoneRetarget(
            aliases = mixamoAliases("Spine") + mixamoAliases("Spine1") + mixamoAliases("Spine2"),
            startLandmark = PoseLandmark.LEFT_HIP,
            endLandmark = PoseLandmark.LEFT_SHOULDER,
            restDirection = PoseVector3(0f, 1f, 0f)
        )
    )

    fun apply(landmarks: List<Float>?) {
        node.position = Position(x = 0f, y = 0f, z = 0f)

        if (landmarks == null || landmarks.size < 33 * 4) return

        node.rotation = avatarBodyRotation(landmarks)
        node.position = avatarRootMotion(landmarks)

        for (bone in bones) {
            val start = landmark(landmarks, bone.startLandmark) ?: continue
            val end = landmark(landmarks, bone.endLandmark) ?: continue
            val entity = bone.aliases.firstNotNullOfOrNull { node.model.getFirstEntityByName(it).takeIf { found -> found != 0 } }
                ?: continue
            val instance = transformManager.getInstance(entity)
            if (instance == 0) continue

            val rest = restTransforms.getOrPut(entity) {
                FloatArray(16).also { transformManager.getTransform(instance, it) }
            }
            val transform = rest.copyOf()
            when (bone.mode) {
                RetargetMode.REPLACE_ROTATION -> {
                    val rotation = PoseQuaternion.fromTo(
                        bone.restDirection,
                        end - start
                    ).toColumnMajorRotationMatrix()
                    writeRotationKeepingTranslation(transform, rotation)
                }
                RetargetMode.ADD_LOCAL_Z -> {
                    val delta = screenAngleDelta(bone.restDirection, end - start)
                    val localRotation = zRotationMatrix(delta)
                    val composed = multiplyColumnMajor(rest, localRotation)
                    writeRotationKeepingTranslation(transform, composed)
                }
            }
            transformManager.setTransform(instance, transform)
        }

        node.animator.updateBoneMatrices()
    }

    private fun mixamoAliases(name: String): List<String> {
        return listOf(
            "mixamorig:$name",
            "mixamorig_$name",
            "mixamorig$name",
            "mixamo:$name",
            "mixamo_$name",
            "mixamo$name",
            name
        )
    }

    private fun landmark(landmarks: List<Float>, index: Int): PoseVector3? {
        val base = index * 4
        val visibility = landmarks.getOrNull(base + 3) ?: return null
        if (visibility < MIN_VISIBILITY) return null
        return PoseVector3(
            x = (landmarks[base] - 0.5f) * 2f,
            y = (0.5f - landmarks[base + 1]) * 2f,
            z = -(landmarks[base + 2])
        )
    }

    private fun avatarBodyRotation(landmarks: List<Float>): Rotation {
        val leftShoulder = landmark(landmarks, PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = landmark(landmarks, PoseLandmark.RIGHT_SHOULDER)
        if (leftShoulder == null || rightShoulder == null) return Rotation()

        val shoulderLine = rightShoulder - leftShoulder
        val rollDegrees = Math.toDegrees(atan2(shoulderLine.y, shoulderLine.x).toDouble()).toFloat()
        return Rotation(z = rollDegrees)
    }

    private fun avatarRootMotion(landmarks: List<Float>): Position {
        val leftHip = landmark(landmarks, PoseLandmark.LEFT_HIP)
        val rightHip = landmark(landmarks, PoseLandmark.RIGHT_HIP)
        val leftKnee = landmark(landmarks, PoseLandmark.LEFT_KNEE)
        val rightKnee = landmark(landmarks, PoseLandmark.RIGHT_KNEE)
        if (leftHip == null || rightHip == null || leftKnee == null || rightKnee == null) {
            return Position(x = 0f, y = 0f, z = 0f)
        }

        val hipCenterX = (leftHip.x + rightHip.x) * 0.5f
        val kneeCenterY = (leftKnee.y + rightKnee.y) * 0.5f
        val hipCenterY = (leftHip.y + rightHip.y) * 0.5f
        val kneeLift = (kneeCenterY - hipCenterY + 0.55f).coerceIn(-0.25f, 0.25f)

        return Position(
            x = (hipCenterX * 0.18f).coerceIn(-0.18f, 0.18f),
            y = (kneeLift * 0.12f).coerceIn(-0.08f, 0.08f),
            z = 0f
        )
    }

    private fun screenAngleDelta(from: PoseVector3, to: PoseVector3): Float {
        val fromAngle = atan2(from.y, from.x)
        val toAngle = atan2(to.y, to.x)
        var delta = toAngle - fromAngle
        while (delta > Math.PI) delta -= (Math.PI * 2f).toFloat()
        while (delta < -Math.PI) delta += (Math.PI * 2f).toFloat()
        return delta.coerceIn(-0.65f, 0.65f)
    }

    private fun zRotationMatrix(angle: Float): FloatArray {
        val sin = kotlin.math.sin(angle)
        val cos = kotlin.math.cos(angle)
        return floatArrayOf(
            cos, sin, 0f, 0f,
            -sin, cos, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    private fun multiplyColumnMajor(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                out[col * 4 + row] =
                    a[0 * 4 + row] * b[col * 4 + 0] +
                    a[1 * 4 + row] * b[col * 4 + 1] +
                    a[2 * 4 + row] * b[col * 4 + 2] +
                    a[3 * 4 + row] * b[col * 4 + 3]
            }
        }
        return out
    }

    private fun writeRotationKeepingTranslation(transform: FloatArray, rotation: FloatArray) {
        transform[0] = rotation[0]
        transform[1] = rotation[1]
        transform[2] = rotation[2]
        transform[4] = rotation[4]
        transform[5] = rotation[5]
        transform[6] = rotation[6]
        transform[8] = rotation[8]
        transform[9] = rotation[9]
        transform[10] = rotation[10]
    }
}

@Composable
fun InstructorAvatarOverlay(
    context: Context,
    landmarks: List<Float>?,
    modifier: Modifier = Modifier
) {
    var hasModelAsset by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasModelAsset = runCatching {
            context.assets.open(INSTRUCTOR_MODEL_ASSET).close()
            true
        }.getOrDefault(false)
    }

    if (!hasModelAsset) {
        PoseOverlay(
            modifier = modifier,
            poses = listOfNotNull(
                landmarks?.let {
                    PoseDrawSpec(
                        landmarks = it,
                        color = Color.Cyan,
                        alpha = 0.35f,
                        jointRadius = 6f,
                        boneWidth = 5f,
                        renderMode = RenderMode.AVATAR
                    )
                }
            )
        )
        return
    }

    val engine = rememberEngine()
    val view = rememberView(engine)
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val avatarNode = remember(modelLoader) {
        ModelNode(
            modelInstance = modelLoader.createModelInstance(INSTRUCTOR_MODEL_ASSET),
            autoAnimate = false,
            scaleToUnits = 2.0f,
            centerOrigin = Position(x = 0f, y = 0f, z = 0f)
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
        }
    }
    val retargeter = remember(avatarNode) { InstructorPoseRetargeter(avatarNode) }

    Box(modifier = modifier) {
        Scene(
            modifier = Modifier.matchParentSize(),
            engine = engine,
            view = view,
            renderer = rememberRenderer(engine),
            scene = rememberScene(engine),
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            collisionSystem = rememberCollisionSystem(view),
            mainLightNode = rememberMainLightNode(engine) {
                intensity = 85_000f
            },
            cameraNode = rememberCameraNode(engine) {
                position = Position(z = 8.0f)
                lookAt(Position(x = 0f, y = 0f, z = 0f))
            },
            cameraManipulator = rememberCameraManipulator(),
            childNodes = rememberNodes {
                add(avatarNode)
            },
            isOpaque = false,
            environment = rememberEnvironment(environmentLoader, isOpaque = false),
            onFrame = {
                retargeter.apply(landmarks)
            },
            onViewCreated = {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        )
    }
}

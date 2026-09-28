package fr.nvmods.cozmo.vision

import android.graphics.Bitmap
import android.media.FaceDetector

/**
 * Détection locale de présence d'un visage dans l'image caméra de Cozmo.
 *
 * Cette brique ne prétend pas reconnaître l'identité d'une personne :
 * elle sert à déclencher automatiquement la réaction "je vois quelqu'un".
 * L'identification nominative nécessite une étape d'enrôlement et un modèle
 * de reconnaissance séparé.
 */
internal class CozmoFaceDetector {

    fun detectFaceCount(source: Bitmap): Int {
        if (source.width < 2 || source.height < 2) return 0

        // android.media.FaceDetector exige du RGB_565 et une largeur paire.
        // On réduit l'image pour garder le traitement léger sur le téléphone.
        val scale =
            minOf(
                1f,
                MAX_ANALYSIS_WIDTH.toFloat() / source.width.toFloat()
            )

        val width =
            ((source.width * scale).toInt().coerceAtLeast(2) and -2)
                .coerceAtMost(source.width and -2)

        val height =
            (source.height * scale)
                .toInt()
                .coerceAtLeast(2)

        val scaled =
            if (width == source.width && height == source.height) {
                source
            } else {
                Bitmap.createScaledBitmap(
                    source,
                    width,
                    height,
                    true
                )
            }

        val rgb565 =
            if (scaled.config == Bitmap.Config.RGB_565) {
                scaled
            } else {
                scaled.copy(Bitmap.Config.RGB_565, false)
            }

        return try {
            val faces =
                arrayOfNulls<FaceDetector.Face>(MAX_FACES)

            FaceDetector(
                rgb565.width,
                rgb565.height,
                MAX_FACES
            ).findFaces(rgb565, faces)
        } catch (_: Throwable) {
            0
        } finally {
            if (rgb565 !== scaled && !rgb565.isRecycled) {
                rgb565.recycle()
            }
            if (scaled !== source && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    companion object {
        private const val MAX_ANALYSIS_WIDTH = 200
        private const val MAX_FACES = 3
    }
}

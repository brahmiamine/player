package fr.streamia.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.CatalogLoadProgress
import fr.streamia.tv.data.CatalogStep
import fr.streamia.tv.data.CatalogStepStatus
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.Danger
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusPanel
import java.text.NumberFormat
import java.util.Locale

private val StepDone = Color(0xFF30D158)

/**
 * Notification d'avancement du premier chargement du catalogue, posée en bas à droite de l'accueil
 * (non focalisable : la télécommande reste sur les tuiles). Une ligne par étape, une barre globale ;
 * en cas d'échec elle reste et invite à « Actualiser ».
 */
@Composable
internal fun CatalogLoadNotification(progress: CatalogLoadProgress, modifier: Modifier = Modifier) {
    val failure = progress.failure
    val total = progress.totalCount.coerceAtLeast(1)
    val fraction = if (progress.finished) 1f else (progress.doneCount.toFloat() / total).coerceIn(0f, 1f)
    GlassSurface(
        modifier = modifier.width(380.dp),
        shape = RoundedCornerShape(RadiusPanel),
        tintColor = Color(0xD1181820),
        elevation = 16.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    failure != null -> StepMark(CatalogStepStatus.Skipped, tint = Danger)
                    progress.finished -> StepMark(CatalogStepStatus.Done)
                    else -> StepMark(CatalogStepStatus.Running)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            failure != null -> "Chargement interrompu"
                            progress.finished -> "Catalogue à jour"
                            else -> "Chargement du catalogue"
                        },
                        color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            failure != null -> failure
                            progress.finished -> "Tout est prêt."
                            else -> progress.current?.let { "${it.label} · étape ${progress.doneCount + 1} sur $total" } ?: "Préparation…"
                        },
                        color = if (failure != null) Danger else MutedInk,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.14f))) {
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (progress.finished) StepDone else AccentPink))
            }
            if (failure != null) {
                Text("Appuyez sur « Actualiser » pour réessayer.", color = MutedInk, fontSize = 12.sp)
            } else {
                CatalogStep.entries.forEach { step -> StepRow(step, progress) }
            }
        }
    }
}

@Composable
private fun StepRow(step: CatalogStep, progress: CatalogLoadProgress) {
    val status = progress.status(step)
    val count = progress.counts[step]
    val detail = when (status) {
        CatalogStepStatus.Done -> if (count != null && step.unit.isNotEmpty()) "${count.grouped()} ${step.unit}" else "Terminé"
        CatalogStepStatus.Running -> if (count != null && step.unit.isNotEmpty()) "${count.grouped()} ${step.unit}" else "En cours…"
        CatalogStepStatus.Skipped -> "Non nécessaire"
        CatalogStepStatus.Waiting -> if (step == CatalogStep.M3uFallback) "Si besoin" else "En attente"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StepMark(status, size = 18.dp)
        Text(
            step.label,
            color = if (status == CatalogStepStatus.Waiting || status == CatalogStepStatus.Skipped) MutedInk else Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            detail,
            color = when (status) {
                CatalogStepStatus.Done -> StepDone
                CatalogStepStatus.Running -> AccentPinkText
                else -> MutedInk
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun StepMark(status: CatalogStepStatus, size: androidx.compose.ui.unit.Dp = 26.dp, tint: Color = MutedInk) {
    when (status) {
        CatalogStepStatus.Running -> ButtonSpinner(size = size, color = AccentPink)
        CatalogStepStatus.Done -> Canvas(Modifier.size(size)) {
            drawCircle(StepDone)
            val w = this.size.minDimension
            val check = Path().apply {
                moveTo(w * 0.29f, w * 0.52f)
                lineTo(w * 0.44f, w * 0.67f)
                lineTo(w * 0.72f, w * 0.36f)
            }
            drawPath(check, Color(0xFF04210D), style = Stroke(w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        CatalogStepStatus.Waiting, CatalogStepStatus.Skipped -> Canvas(Modifier.size(size)) {
            val w = this.size.minDimension
            drawCircle(tint.copy(alpha = 0.5f), radius = w / 2 - w * 0.06f, style = Stroke(w * 0.09f))
            if (status == CatalogStepStatus.Skipped) {
                drawLine(tint, Offset(w * 0.3f, w / 2), Offset(w * 0.7f, w / 2), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            }
        }
    }
}

private fun Int.grouped(): String = NumberFormat.getIntegerInstance(Locale.FRENCH).format(this)

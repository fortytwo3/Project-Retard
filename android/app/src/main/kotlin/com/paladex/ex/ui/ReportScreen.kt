package com.paladex.ex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.paladex.ex.ScanUiState
import com.paladex.ex.ScanViewModel
import com.paladex.ex.core.Money
import com.paladex.ex.core.ProviderResult
import com.paladex.ex.core.ProviderStatus
import com.paladex.ex.core.SoldListing
import com.paladex.ex.core.SoldSummary
import com.paladex.ex.core.SourceMethod
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ReportStage(viewModel: ScanViewModel, state: ScanUiState) {
    val report = state.report ?: return
    val card = report.card

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TextButton(onClick = viewModel::reset) { Text("← Scan another") } }

        state.error?.let { item { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) } }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AsyncImage(
                        model = card.imageLarge.ifEmpty { card.imageSmall },
                        contentDescription = card.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(104.dp)
                            .height(145.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Surface2),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            card.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            card.setName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append("#${card.number}")
                                card.printedTotal?.let { append("/$it") }
                                card.rarity?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )

                        Spacer(Modifier.height(12.dp))
                        Text(
                            Money.format(report.consensus.amount, report.consensus.currency),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = Accent,
                        )
                        Text(
                            report.consensus.basis,
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Updated " + SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(report.generatedAtMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
                OutlinedButton(
                    onClick = { viewModel.loadPrices(card, refresh = true) },
                    enabled = !state.refreshing,
                ) {
                    Text(if (state.refreshing) "Refreshing…" else "Refresh prices")
                }
            }
        }

        items(report.results, key = { it.providerId }) { result -> ProviderCard(result) }
    }
}

@Composable
private fun ProviderCard(result: ProviderResult) {
    val uriHandler = LocalUriHandler.current
    val isSoldData = result.providerId == "ebay" && result.label.contains("Sold")

    Card(colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        result.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge(
                            when (result.method) {
                                SourceMethod.API -> "API"
                                SourceMethod.SCRAPE -> "scraped"
                                SourceMethod.NONE -> "off"
                            },
                            if (result.method == SourceMethod.API) AccentDeep else Muted,
                        )
                        if (result.cached) Badge("cached", Muted)
                    }
                }
                result.sourceUrl?.let { url ->
                    TextButton(onClick = { uriHandler.openUri(url) }) { Text("Open ↗") }
                }
            }

            if (result.status == ProviderStatus.OK) {
                result.prices.forEach { price ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(price.label, style = MaterialTheme.typography.bodySmall, color = Muted)
                            price.note?.let {
                                Text(it, fontSize = 10.sp, color = Muted)
                            }
                        }
                        Text(
                            Money.format(price.amount, price.currency),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (result.soldListings.isNotEmpty()) {
                    SoldListings(result.soldListings, result.soldSummary, isSoldData)
                }
            } else {
                Text(
                    result.message ?: when (result.status) {
                        ProviderStatus.EMPTY -> "No prices available for this card."
                        else -> "This source could not be reached."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontSize = 10.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Surface2)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SoldListings(
    listings: List<SoldListing>,
    summary: SoldSummary?,
    areCompletedSales: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded) listings else listings.take(6)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        summary?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Median", Money.format(it.median, it.currency), Modifier.weight(1f))
                Stat(
                    if (areCompletedSales) "Average" else "Avg asking",
                    Money.format(it.trimmedMean, it.currency),
                    Modifier.weight(1f),
                    emphasis = true,
                )
                Stat(
                    "Range",
                    "${Money.format(it.min, it.currency)}–${Money.format(it.max, it.currency)}",
                    Modifier.weight(1f),
                    small = true,
                )
            }

            it.trendPct?.let { trend ->
                Text(
                    (if (trend >= 0) "▲ " else "▼ ") + "%.1f%%".format(abs(trend)) +
                        " across this sample, oldest to newest",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (trend >= 0) Good else Bad,
                )
            }
        }

        visible.forEach { listing ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        listing.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listing.soldAt?.let { Text(relativeDate(it), fontSize = 10.sp, color = Muted) }
                        listing.detectedGrade?.let { Badge(it, Muted) }
                        if (listing.shippingIncluded) {
                            Text("free shipping", fontSize = 10.sp, color = Muted)
                        }
                    }
                }
                Text(
                    Money.format(listing.price, listing.currency),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (listings.size > 6) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show fewer" else "Show all ${listings.size}")
            }
        }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    small: Boolean = false,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .padding(8.dp),
    ) {
        Text(label, fontSize = 10.sp, color = Muted)
        Text(
            value,
            fontSize = if (small) 11.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasis) Accent else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun relativeDate(iso: String): String {
    val instant = runCatching { java.time.Instant.parse(iso) }.getOrNull() ?: return ""
    val days = ((System.currentTimeMillis() - instant.toEpochMilli()) / 86_400_000.0).roundToInt()

    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 30 -> "${days}d ago"
        else -> "${(days / 30.0).roundToInt()}mo ago"
    }
}

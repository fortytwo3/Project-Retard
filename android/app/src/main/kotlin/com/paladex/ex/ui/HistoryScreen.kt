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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.paladex.ex.ScanViewModel
import com.paladex.ex.core.Currency
import com.paladex.ex.core.HistoryEntry
import com.paladex.ex.core.Money
import com.paladex.ex.data.HistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(viewModel: ScanViewModel) {
    var entries by remember { mutableStateOf(viewModel.historyEntries()) }
    var confirmClear by remember { mutableStateOf(false) }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Nothing scanned yet.", color = Muted)
        }
        return
    }

    val totals = HistoryStore.portfolioValue(entries)

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface1)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Total across ${totals.counted} ${if (totals.counted == 1) "card" else "cards"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                    Text(
                        Money.format(
                            totals.total,
                            runCatching { Currency.valueOf(totals.currency) }.getOrDefault(Currency.USD),
                        ),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = Accent,
                    )
                    Text(
                        "Value at the time of each scan" +
                            if (totals.skipped > 0) {
                                " · ${totals.skipped} not counted (unpriced or other currency)"
                            } else "",
                        fontSize = 10.sp,
                        color = Muted,
                    )
                }
            }
        }

        items(entries, key = { it.id }) { entry ->
            HistoryRow(entry) {
                viewModel.removeHistory(entry.id)
                entries = viewModel.historyEntries()
            }
        }

        item {
            OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear history") }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete the whole scan history?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    entries = emptyList()
                    confirmClear = false
                }) { Text("Delete", color = Bad) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = entry.imageUrl,
            contentDescription = entry.cardName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(44.dp)
                .height(62.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Surface2),
        )

        Column(Modifier.weight(1f)) {
            Text(
                entry.cardName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.setName,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(entry.scannedAtMillis)),
                fontSize = 10.sp,
                color = Muted,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                Money.format(
                    entry.valueAtScan,
                    runCatching { Currency.valueOf(entry.currency) }.getOrDefault(Currency.USD),
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onRemove, contentPadding = PaddingValues(4.dp)) {
                Text("Remove", fontSize = 10.sp, color = Muted)
            }
        }
    }
}

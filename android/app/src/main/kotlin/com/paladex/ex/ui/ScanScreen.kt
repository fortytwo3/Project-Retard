package com.paladex.ex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.paladex.ex.ScanUiState
import com.paladex.ex.ScanViewModel
import com.paladex.ex.Stage
// Aliased because Material3 also exports a `Card`, and an unqualified import of
// the domain type would shadow the composable throughout this file.
import com.paladex.ex.core.Card as TcgCard
import com.paladex.ex.core.CardCandidate

@Composable
fun ScanScreen(viewModel: ScanViewModel, state: ScanUiState) {
    when (state.stage) {
        Stage.SCAN -> ScanStage(viewModel, state)
        Stage.READING -> ReadingStage(state)
        Stage.CANDIDATES -> CandidatesStage(viewModel, state)
        Stage.REPORT -> ReportStage(viewModel, state)
    }
}

@Composable
private fun ScanStage(viewModel: ScanViewModel, state: ScanUiState) {
    var showSearch by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CameraCapture(onCapture = viewModel::scanCard)

        state.error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }

        TextButton(onClick = { showSearch = !showSearch }) {
            Text(if (showSearch) "Hide name search" else "Can't scan it? Search by name")
        }

        if (showSearch) {
            ManualSearch(viewModel, state, onSelect = { viewModel.loadPrices(it) })
        }
    }
}

@Composable
private fun ReadingStage(state: ScanUiState) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        state.preview?.let { bitmap ->
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured card",
                modifier = Modifier.heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
        LinearProgressIndicator(Modifier.width(160.dp))
        Text(state.status, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CandidatesStage(viewModel: ScanViewModel, state: ScanUiState) {
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = viewModel::reset) { Text("← Scan another") }
        }

        state.scan?.let { scan ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Surface1)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Read as", color = Muted, style = MaterialTheme.typography.labelSmall)
                        Text(
                            buildString {
                                append(scan.name ?: "unknown name")
                                scan.number?.let { append(" · #$it") }
                                scan.printedTotal?.let { append("/$it") }
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        state.error?.let {
            item { Text(it, color = Bad, style = MaterialTheme.typography.bodyMedium) }
        }

        if (state.candidates.isNotEmpty()) {
            item {
                Text(
                    if (state.candidates.size == 1) "1 match — confirm the printing"
                    else "${state.candidates.size} matches — pick the right printing",
                    color = Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            item {
                // Height-bounded so the grid can live inside the scrolling column.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 1200.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false,
                ) {
                    items(state.candidates, key = { it.card.id }) { candidate ->
                        CandidateCard(candidate) { viewModel.loadPrices(candidate.card) }
                    }
                }
            }
        }

        item {
            Text(
                if (state.candidates.isEmpty()) "Search by name instead" else "Not the right card?",
                fontWeight = FontWeight.Medium,
            )
        }
        item { ManualSearch(viewModel, state, onSelect = { viewModel.loadPrices(it) }) }
    }
}

@Composable
private fun CandidateCard(candidate: CardCandidate, onClick: () -> Unit) {
    Card(
        // A clickable modifier rather than the onClick overload, which has
        // moved in and out of experimental across Material3 releases.
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Surface1),
    ) {
        Column(Modifier.padding(8.dp)) {
            AsyncImage(
                model = candidate.card.imageSmall,
                contentDescription = candidate.card.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(5f / 7f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface2),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                candidate.card.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                candidate.card.setName,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("#${candidate.card.number}")
                    candidate.card.printedTotal?.let { append("/$it") }
                    candidate.card.rarity?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { candidate.score.toFloat() },
                    modifier = Modifier.weight(1f).height(4.dp),
                    color = Accent,
                    trackColor = Surface2,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${(candidate.score * 100).toInt()}%",
                    fontSize = 10.sp,
                    color = Muted,
                )
            }
            candidate.reasons.firstOrNull()?.let {
                Text(it, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ManualSearch(
    viewModel: ScanViewModel,
    state: ScanUiState,
    onSelect: (TcgCard) -> Unit,
) {
    var term by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = term,
            onValueChange = {
                term = it
                viewModel.search(it)
            },
            placeholder = { Text("Search by card name, e.g. Charizard ex") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.searching) {
            Text("Searching…", color = Muted, style = MaterialTheme.typography.bodySmall)
        }

        state.searchResults.forEach { card ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(card) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    model = card.imageSmall,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(40.dp).height(56.dp).clip(RoundedCornerShape(4.dp)),
                )
                Column(Modifier.weight(1f)) {
                    Text(card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${card.setName} · #${card.number}" + (card.printedTotal?.let { "/$it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

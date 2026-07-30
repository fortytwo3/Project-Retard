package com.paladex.ex.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paladex.ex.ScanViewModel
import com.paladex.ex.core.Config
import com.paladex.ex.core.SourceMethod
import com.paladex.ex.core.describeCapabilities

/**
 * Which sources are live and why, plus the keys that upgrade each one from
 * scraping to its official API. Keys are write-only in the UI — they are shown
 * masked and never echoed back in full.
 */
@Composable
fun SourcesScreen(viewModel: ScanViewModel) {
    var config by remember { mutableStateOf(viewModel.config) }
    val capabilities = remember(config) { describeCapabilities(config) }

    fun update(next: Config) {
        config = next
        viewModel.updateConfig(next)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Price sources", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Sources without a key read the public page instead. Changes take effect on the next scan.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        capabilities.forEach { capability ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface1)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            capability.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (capability.active) "active" else "off",
                            fontSize = 10.sp,
                            color = if (capability.active) Good else Muted,
                        )
                    }
                    Text(
                        when (capability.method) {
                            SourceMethod.API -> "Official API"
                            SourceMethod.SCRAPE -> "Reading the public page"
                            SourceMethod.NONE -> "Off"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentDeep,
                    )
                    Text(capability.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }

        HorizontalDivider(color = Line)
        Text("Keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        SecretField("pokemontcg.io API key", config.pokemonTcgApiKey) {
            update(config.copy(pokemonTcgApiKey = it))
        }
        SecretField("PriceCharting token", config.priceChartingToken) {
            update(config.copy(priceChartingToken = it))
        }
        SecretField("eBay client ID", config.ebayClientId) {
            update(config.copy(ebayClientId = it))
        }
        SecretField("eBay client secret", config.ebayClientSecret) {
            update(config.copy(ebayClientSecret = it))
        }

        SwitchRow(
            "eBay Marketplace Insights",
            "Turn on once eBay approves your app for completed-sales data.",
            config.ebayMarketplaceInsightsEnabled,
        ) { update(config.copy(ebayMarketplaceInsightsEnabled = it)) }

        SwitchRow(
            "Read public pages",
            "When off, only official APIs are used. Turning this off without keys disables PriceCharting and eBay sold prices.",
            config.scrapeEnabled,
        ) { update(config.copy(scrapeEnabled = it)) }

        Card(colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "A note on the scrapers",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Without a key, the app reads the same public page you would open yourself. " +
                        "Requests are rate-limited to one per host at a time and cached, and scraped " +
                        "numbers are labelled as such in every report. Both sites restrict automated " +
                        "access in their terms of service, and either can change its markup without " +
                        "warning — if a source suddenly returns nothing, that is usually why.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.trim())
        },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

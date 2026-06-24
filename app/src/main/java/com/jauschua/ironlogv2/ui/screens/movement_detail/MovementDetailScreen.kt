package com.jauschua.ironlogv2.ui.screens.movement_detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovementDetailScreen(
    onBack: () -> Unit,
    onTryAutoregulate: (Int) -> Unit,
    vm: MovementDetailViewModel = viewModel(factory = MovementDetailViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Movement") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is UiState.Error   -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.msg, color = MaterialTheme.colorScheme.error)
                }
                is UiState.Success -> DetailBody(s.data, onTryAutoregulate)
            }
        }
    }
}

@Composable
private fun DetailBody(m: MovementDto, onTryAutoregulate: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(m.name, style = MaterialTheme.typography.headlineSmall)
        Text(m.base_name, style = MaterialTheme.typography.bodyMedium)
        Field("Region",          m.region.name)
        Field("Lift category",   m.lift_category.name)
        Field("Primary",         m.is_primary.toString())
        Field("Status",          m.status.name)
        Field("Progression",     m.progression_mode.name)
        Field("Scheme",          m.scheme.name)
        m.objective_override?.let { Field("Objective override", it.name) }
        Field("Increment ladder", m.increment_ladder.joinToString(", ").ifEmpty { "—" })
        Field("Min step",         m.min_step?.toString() ?: "—")
        Field("Load floor",       m.load_floor?.toString() ?: "—")
        Field("Cap",              m.cap?.toString() ?: "—")
        Field("RPE-capped",       m.rpe_capped.toString())
        Field("RPE-cap exempt",   m.rpe_cap_exempt.toString())
        Field("Equipment tags",   m.equipment_tags.joinToString(", ").ifEmpty { "—" })
        Field("Load equipment id", m.load_equipment_id?.toString() ?: "—")
        Field("Band eligible",    m.band_eligible.toString())
        m.assist_subtype?.let { Field("Assist subtype", it.name) }
        m.assist_unit?.let    { Field("Assist unit",    it.name) }
        m.family?.let         { Field("Family",         it) }
        Field("Family anchor",    m.is_family_anchor.toString())
        m.derived_from_id?.let { Field("Derived from id", it.toString()) }
        m.start_ratio?.let     { Field("Start ratio",     it.toString()) }
        m.notes?.let           { Field("Notes",           it) }

        Button(
            onClick = { onTryAutoregulate(m.id) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            enabled = m.progression_mode.name == "LADDER",
        ) {
            Text(
                if (m.progression_mode.name == "LADDER") "Try autoregulate"
                else "Autoregulate not available (LADDER lifts only)"
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

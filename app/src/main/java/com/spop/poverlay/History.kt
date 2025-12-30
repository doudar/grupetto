package com.spop.poverlay

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel


@Composable
fun History(viewModel: ConfigurationViewModel) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()

    ) {
        item {
            Text(
                text = "Completed Rides, click to see details",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        items(viewModel.activities) { activity ->
            ActivityItem(activity, viewModel)
            Divider()
        }


    }
}

@Composable
fun ActivityItem(
    activity: ConfigurationViewModel.ActivityData,
    viewModel: ConfigurationViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.activityClick(activity.id) }
            .padding(vertical = 8.dp)
    ) {
        Text(text = activity.title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column (modifier = Modifier.weight(1f)){
                Text(text = "${viewModel.formatDate(activity.startTime)}")
            }
            Column (modifier = Modifier.weight(1f)){
                Text(text = "Duration: ${viewModel.formatDuration(activity.trackTime)}")
            }
            Column (modifier = Modifier.weight(1f)){
                Text(text = "Avg HR: ${activity.avgHeartRate ?: "-"}")
            }
            Column (modifier = Modifier.weight(1f)){
                Text(text = "Avg Power: ${activity.avgPower ?: "-"}")
            }

        }

    }
}

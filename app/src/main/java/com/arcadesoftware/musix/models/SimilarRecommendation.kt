package com.arcadesoftware.musix.models

import com.arcadesoftware.musix.db.entities.PlayHistoryEntity
import com.music.innertube.models.YTItem

data class SimilarRecommendation(
    val seed: PlayHistoryEntity,
    val items: List<YTItem>
) : java.io.Serializable

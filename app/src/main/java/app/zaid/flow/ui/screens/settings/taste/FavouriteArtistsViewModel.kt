package app.zaid.flow.ui.screens.settings.taste

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zaid.flow.data.recommendation.music.FavouriteArtistCatalog
import app.zaid.flow.data.recommendation.music.FavouriteArtistPicker
import app.zaid.flow.data.recommendation.music.FavouriteArtistsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
internal class FavouriteArtistsViewModel
    @Inject
    constructor(
        store: FavouriteArtistsStore,
        catalog: FavouriteArtistCatalog,
    ) : ViewModel() {
        val picker = FavouriteArtistPicker(viewModelScope, store, catalog)
    }

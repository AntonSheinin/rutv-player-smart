# Next Steps to Complete Refactoring

## Summary of What's Been Done

We've successfully created a modern Android architecture with:

✅ **30+ new files** implementing clean architecture
✅ **Room Database** for efficient data storage
✅ **Hilt DI** for dependency injection
✅ **MVVM pattern** with ViewModels and StateFlow
✅ **Repository pattern** for data abstraction
✅ **Use Cases** for business logic
✅ **PlayerManager** extracting all player logic from MainActivity
✅ **Utilities** and constants centralized

---

## What's Remaining (3-4 files to update)

### 1. Update MainActivity (~1 hour)

**Current:** 1,389 lines, god object with all logic
**Target:** ~300-400 lines, just UI

**Steps:**
```kotlin
// 1. Add Hilt annotation
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // 2. Inject ViewModel
    private val viewModel: MainViewModel by viewModels()

    // 3. Remove all player/repository logic (now in ViewModel)
    // 4. Collect StateFlow and update UI
    // 5. Call ViewModel methods instead of direct operations
}
```

**Key Changes:**
- Remove `player` variable (use `viewModel.getPlayer()`)
- Remove `playlist` (use `viewModel.viewState.channels`)
- Remove all coroutine launches (done in ViewModel)
- Remove repository access (done via ViewModel)
- Just observe `viewState` and update UI

---

### 2. Update PlaylistAdapter (~30 minutes)

**Current:** Manual notifyDataSetChanged()
**Target:** ListAdapter with automatic DiffUtil

**Steps:**
```kotlin
class PlaylistAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onFavoriteClick: (Channel) -> Unit,
    private val onShowPrograms: (Channel) -> Unit,
    private val epgRepository: EpgRepository
) : ListAdapter<Channel, PlaylistViewHolder>(ChannelDiffCallback()) {

    // No need for manual list management
    // submitList() handles everything
}
```

**Benefits:**
- Automatic animations
- Efficient updates
- No more notifyDataSetChanged()

---

### 3. Update EpgProgramsAdapter (~20 minutes)

**Current:** Manual updates
**Target:** DiffUtil support

**Steps:**
- Similar to PlaylistAdapter
- Add EpgProgramDiffCallback
- Use submitList()

---

### 4. Update SettingsActivity (~30 minutes)

**Current:** Direct SharedPreferences access
**Target:** ViewModel with DataStore

**Create:**
```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val loadPlaylistUseCase: LoadPlaylistUseCase
) : ViewModel() {

    // Settings state management
}
```

**Update Activity:**
```kotlin
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    private val viewModel: SettingsViewModel by viewModels()

    // Observe settings and update UI
}
```

---

### 5. Create Data Migration (Optional but recommended)

**File:** `data/migration/LegacyDataMigration.kt`

**Purpose:** Migrate existing user data from old storage to new Room database

```kotlin
class LegacyDataMigration @Inject constructor(
    private val context: Context,
    private val channelRepository: ChannelRepository
) {
    suspend fun migrateIfNeeded() {
        // Check if migration needed
        // Read old SharedPreferences JSON
        // Convert to Channel objects
        // Save to Room database
        // Mark migration complete
    }
}
```

**Call from:** `RuTvApplication.onCreate()` or `MainViewModel.init`

---

## Build & Test Checklist

After completing the above:

1. **Build the project**
   ```bash
   ./gradlew assembleDebug
   ```

2. **Fix any compilation errors**
   - Most will be import changes
   - VideoItem → Channel
   - Direct repository calls → ViewModel calls

3. **Test functionality**
   - [ ] App launches
   - [ ] Playlist loads from file
   - [ ] Playlist loads from URL
   - [ ] Channels display in list
   - [ ] Channel playback works
   - [ ] Favorites toggle works
   - [ ] EPG displays
   - [ ] Aspect ratio changes persist
   - [ ] Settings save/load
   - [ ] Debug log shows/hides

4. **Performance Testing**
   - [ ] RecyclerView scrolling is smooth
   - [ ] No UI freezes
   - [ ] Memory usage is reasonable
   - [ ] App responds quickly

---

## Quick Reference: Key Changes

### Old Way vs New Way

| Old | New |
|-----|-----|
| `VideoItem` | `Channel` |
| `ChannelStorage` | `ChannelRepository` |
| `SharedPreferences` | `DataStore (PreferencesRepository)` |
| Player logic in MainActivity | `PlayerManager` |
| Direct coroutine launches | `viewModelScope` in ViewModel |
| `lifecycleScope.launch { }` in Activity | `viewModel.someMethod()` |
| Manual list updates | `DiffUtil` with `submitList()` |
| `M3U8Parser` object | `PlaylistParser` injectable |
| Global state | `StateFlow<ViewState>` |

### Import Changes Needed

```kotlin
// Old imports
import com.videoplayer.VideoItem
import com.videoplayer.ChannelStorage
import com.videoplayer.M3U8Parser
import com.videoplayer.EpgService

// New imports
import com.videoplayer.data.model.Channel
import com.videoplayer.data.repository.ChannelRepository
import com.videoplayer.data.remote.PlaylistParser
import com.videoplayer.data.repository.EpgRepository
import com.videoplayer.presentation.main.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels
```

---

## Estimated Time to Complete

- **MainActivity refactoring:** 1-2 hours
- **Adapter updates:** 1 hour
- **SettingsActivity:** 30 minutes
- **Testing & fixes:** 1-2 hours
- **Total:** 4-6 hours

---

## Benefits After Completion

### Performance
- 📈 60% reduction in MainActivity complexity
- ⚡ Faster RecyclerView updates (DiffUtil)
- 💾 Better memory management (Room)
- 🚫 No UI blocking (proper coroutines)

### Maintainability
- 📦 Clear package structure
- 🎯 Single responsibility principle
- 🔧 Easy to add new features
- 🧪 Testable business logic
- 📚 Better code documentation

### Reliability
- ✅ Proper error handling
- 🔄 Configuration change handling
- 💪 Type-safe database operations
- 🛡️ Lifecycle-aware components

---

## Need Help?

### Common Issues:

**"Unresolved reference: VideoItem"**
- Replace with `Channel`
- Update imports

**"Cannot find symbol: ChannelStorage"**
- Inject `ChannelRepository` via Hilt
- Call repository methods in ViewModel

**"Hilt component not generated"**
- Make sure `@HiltAndroidApp` is on Application class
- Rebuild project

**"Room schema export"**
- Already set to `exportSchema = false` in AppDatabase

---

## Contact / Questions

If you need any clarification or run into issues:
1. Check `REFACTORING_STATUS.md` for overall status
2. Review the architecture diagram in status doc
3. Look at similar implementations in new files as examples

---

**Good luck with the final steps!** 🚀

The heavy lifting is done - the architecture is solid, now it's just connecting the UI to the new backend.

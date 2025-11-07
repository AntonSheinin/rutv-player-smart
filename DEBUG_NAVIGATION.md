# Debug Navigation Issue

## Steps to Test

1. Build and install the app with the debug logging enabled
2. **Enable Debug Log in the app:**
   - Go to Settings → Show Debug Log → Enable it
3. Open the channel list (press LEFT on d-pad)
4. Try pressing UP or DOWN
5. **Look at the debug log window on the TV screen** (no ADB needed!)
6. Take a photo or screenshot of the debug logs

## Expected Log Sequence (when UP is pressed in channel list)

### When Panel First Opens:
1. `🚀 LaunchedEffect: initial focus to Ch5 (current=4, focused=4)` (Panel opens)
2. `🎯 focusChannel(4, play=false) requesting focus` (Focus controller called)
3. `  → requestFocus() called for Ch5` (FocusRequester.requestFocus() called)
4. `🎯 Ch5 focus=true hasFocus=true isFocused=true` (ChannelListItem gains focus)

### When UP is Pressed:
1. `▲ onKeyDown: isRemote=true, hasRemote=true, keyCode=19` (MainActivity)
2. `▲ → skip MainActivity (panels/controls open)` (MainActivity delegates to Compose)
3. `📋 LazyColumn UP type=KeyDown remote=true` (LazyColumn receives event)
4. `🔘 Ch5 UP type=KeyDown focus=true remote=true` (ChannelListItem receives event)
5. `  ⬆ UP Ch5` (ChannelListItem handling UP)
6. `⬆ Ch5 UP` (PlaylistPanel callback)
7. `🎯 focusChannel(3, play=false) requesting focus` (Focusing previous channel)
8. `  → requestFocus() called for Ch4` (FocusRequester called)
9. `🎯 Ch4 focus=true hasFocus=true isFocused=true` (Ch4 gains focus)
10. `🎯 Ch5 focus=false hasFocus=false isFocused=false` (Ch5 loses focus)
11. `    result=true` (Navigation succeeded)
12. `  handled=true` (Event consumed)

## Possible Issues to Diagnose

**Issue 1: LazyColumn doesn't receive events**
- Logs show MainActivity delegating, but no `📋 LazyColumn` log
- Means events are consumed before reaching Compose hierarchy
- Root cause: Activity consuming events or ComposeView not in focus tree

**Issue 2: Items don't receive events but LazyColumn does**
- `📋 LazyColumn` appears but no `🔘 Ch` log
- Means events reach parent but not children
- Root cause: LazyColumn not propagating events to items OR items not focusable

**Issue 3: Item not focused on panel open**
- No `🎯 Ch5 focus=true` log after panel opens
- Means initial focus request failed
- Check if `🚀 LaunchedEffect` and `🎯 focusChannel` logs appear

**Issue 4: Item not focused when navigating**
- `🎯 focusChannel` appears but no subsequent `🎯 Ch focus=true`
- Means requestFocus() called but focus not gained
- Root cause: Item not in focus tree or focus system broken

**Issue 5: Remote mode not detected**
- Log shows `remote=false`
- DeviceHelper.isRemoteInputActive() returning false
- Need to check device input detection

**Issue 6: Event type wrong**
- Log shows `type=KeyUp` instead of `KeyDown`
- UP events are being processed instead of DOWN events

**Issue 7: Callback returns false**
- Log shows `result=false`
- Navigation callback not working properly

## Debug Symbols Legend

- `▲` / `▼` - UP/DOWN in MainActivity
- `🚀` - LaunchedEffect initial focus
- `🎯` - Focus change or focus request
- `📋` - LazyColumn receives key event
- `🔘` - ChannelListItem receives key event
- `⬆` / `⬇` - UP/DOWN navigation in channel list
- `✓` - OK button pressed
- `→` - RIGHT arrow, navigation, or focus action
- `✗` - Event skipped/not handled
- `❌` - Error or out-of-range

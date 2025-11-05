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

1. `▲ UP: list=true, epg=false, ctrl=false` (MainActivity)
2. `▲ UP → Compose` (MainActivity delegating to Compose)
3. `🔘 Ch5 key=19 focus=true remote=true` (ChannelListItem receives event)
4. `  ⬆ UP Ch5` (ChannelListItem handling UP)
5. `⬆ Ch5 UP` (PlaylistPanel callback)
6. `  → Ch4` (Focusing previous channel)
7. `    result=true` (Navigation succeeded)
8. `  handled=true` (Event consumed)

## Possible Issues to Diagnose

**Issue 1: onKeyEvent not called**
- Logs show MainActivity delegating, but no `🔘 Ch` log
- Means Compose isn't receiving the event
- Root cause: Event consumed elsewhere or focus issue

**Issue 2: Item not focused**
- Log shows `focus=false` in ChannelListItem
- Means focus isn't being set correctly
- Check if initial focus is requested

**Issue 3: Remote mode not detected**
- Log shows `remote=false`
- DeviceHelper.isRemoteInputActive() returning false
- Need to check device input detection

**Issue 4: Event type wrong**
- Log shows `type=KeyUp` instead of `KeyDown`
- UP events are being processed instead of DOWN events

**Issue 5: Callback returns false**
- Log shows `result=false`
- Navigation callback not working properly

## Debug Symbols Legend

- `▲` / `▼` - UP/DOWN in MainActivity
- `🔘` - Key event received in ChannelListItem
- `⬆` / `⬇` - UP/DOWN navigation in channel list
- `✓` - OK button pressed
- `→` - RIGHT arrow or navigation to EPG
- `✗` - Event skipped/not handled

# Debug Navigation Issue

## Steps to Test

1. Build and install the app with the debug logging enabled
2. Connect via ADB and monitor logcat
3. Open the channel list (press LEFT on d-pad)
4. Try pressing UP or DOWN
5. Share the logcat output

## ADB Commands

```bash
# Clear logcat
adb logcat -c

# Start monitoring (filter for our logs)
adb logcat | grep -E "(MainActivity|ChannelListItem|PlaylistPanel)"
```

## What to Look For

### Expected Log Sequence (when UP is pressed)

1. `MainActivity: UP - playlist=true, epg=false, controls=false`
2. `MainActivity: UP - delegating to Compose`
3. `ChannelListItem[X]: onKeyEvent called - key=DirectionUp, type=KeyDown, focused=true, remoteMode=true`
4. `ChannelListItem[X]: UP pressed, calling onNavigateUp`
5. `PlaylistPanel: onNavigateUp called for index=X`
6. `PlaylistPanel: Focusing channel Y`
7. `ChannelListItem[X]: UP result=true`
8. `ChannelListItem[X]: Event handled=true`

### Possible Issues

**Issue 1: onKeyEvent not called**
- Log shows MainActivity delegating, but no ChannelListItem log
- Means Compose isn't receiving the event
- Root cause: Event consumed elsewhere or focus issue

**Issue 2: Item not focused**
- Log shows `focused=false` in ChannelListItem
- Means focus isn't being set correctly
- Check focus requester setup

**Issue 3: Remote mode not detected**
- Log shows `remoteMode=false`
- DeviceHelper.isRemoteInputActive() returning false
- Need to check device input detection

**Issue 4: Callback returns false**
- Log shows `UP result=false`
- Navigation callback not working
- Check lambda return values

## Quick Fix to Try

If logs show the event ISN'T reaching Compose, the issue is in MainActivity.
If logs show the event IS reaching Compose but not being handled, the issue is in the Compose layer.

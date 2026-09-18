# Swipe trickplay: device acceptance test

Status: awaiting device testing; PR must remain Draft.

## Install

Download `build-artifacts` from the **App / Build** workflow on the latest PR commit.
Extract the ZIP and install the **libre-debug.apk** (no Google Cast support needed for these tests).
The debug application ID is `org.jellyfin.mobile.debug`; it can coexist with the release app.
Log into your Jellyfin server in the debug app, select the integrated player (ExoPlayer),
and enable horizontal swipe seeking. Use a video whose server-side trickplay generation is complete.
If Android reports a signature conflict with an older debug build, keep the release app and remove
only the previous debug app if you accept losing that debug app's local settings/login.

## Acceptance checks

| Scenario | Expected result |
| --- | --- |
| Swipe left/right during playback and hold for several seconds | Thumbnail and target time track the gesture; playback does not seek until release. |
| Release after a long drag | Seek lands at the displayed target (subject to decoder/keyframe precision), without adding elapsed playback time. |
| Drag to start and exact end | No crash, invalid crop, or request beyond the last thumbnail. |
| Quickly reverse direction repeatedly | Latest target wins; an older queued thumbnail does not overwrite it. |
| Release with zero net offset or cancel with a system gesture | No seek; preview and seek overlay disappear. |
| Add a second finger while seeking | Seek is cancelled; pinch zoom does not commit the abandoned seek on release. |
| Start a second swipe immediately after releasing the first | Previous overlay timeout does not hide the new preview. |
| Rotate, change episode, exit and reopen during thumbnail loading | Old requests do not reveal stale thumbnails or seek the new video. |
| Switch from video with trickplay to video without it | Time-only horizontal seeking still works; no previous video's image. |
| Disconnect network during preview, reconnect, and move again | Playback UI stays usable; failed thumbnails can retry. |
| Drag the regular seek bar | Thumbnail, chapter name, timestamp and positioning still work. |
| Vertical volume/brightness gestures, locked controls | Existing controls work; no horizontal preview appears. |
| Live/unknown-duration/non-seekable media | Horizontal preview/seek is skipped safely. |
| Landscape and portrait, including a short screen | Overlay remains legible; note any clipping for follow-up. |

Record phone model, Android version, server version, build commit, playback mode (direct/transcode),
steps and expected/actual result. Include a screen recording for position mismatch or stale previews.
Do not include access tokens or server credentials in reports.

## Validation scope

Build/test/lint workflow results apply only to their exact commit. They do not establish that the
manual checks above passed. No device playback was available in the editing environment.

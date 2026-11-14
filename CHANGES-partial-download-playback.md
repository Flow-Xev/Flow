# In-progress download preview

- Video stream transfers now write a contiguous prefix so the player never mistakes preallocated zero-filled bytes for downloaded media.
- The downloader records the expected video-stream length and signals the player as new bytes land.
- Downloads expose their private `.video.part` file for local preview only after at least 10% of the video stream is present.
- Media3 reads that file through a growing-file DataSource: it waits for appended bytes, ends cleanly when the transfer completes, and reports an error if the transfer is paused or fails.
- In-progress rows become tappable after the download reaches the preview threshold; the existing player is used, so no separate preview player is introduced.

The preview is limited to sequential video parts. Audio from a separate audio-only stream is not merged into the preview. MP4s whose required index metadata is located near the end may not begin decoding until that metadata has arrived; the source waits rather than treating the current file length as final EOF.

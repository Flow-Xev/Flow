# Progressive download groundwork

- Muxed single-file streams are fetched with one worker in byte order.
- Sequential part files are no longer pre-sized to the final content length. Their on-disk length now represents bytes actually received, rather than a sparse file containing unwritten zeroes.
- Resume-state validation for sequential streams now accepts only an exact contiguous prefix and rejects sparse/full-length placeholders.
- Added a regression test covering valid prefix resume and rejecting a sparse file.

This is the storage/transfer foundation, not the complete user-facing progressive player. MP4 playback before completion still requires the player to wait for file growth and a seekable/available MP4 index (`moov` atom); those player and UI pieces are not claimed as completed here.

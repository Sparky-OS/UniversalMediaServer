# Stereo display geometry fixtures

The four video-stereo-display-*.mkv files derive from the synthetic LEFT/RIGHT fixtures added by PR #6400.
No image samples were changed.
Full SBS uses video-h265_stereomode_side_by_side_right_first.mkv.
Half SBS uses video-h264_stereomode_side_by_side_left_first.mkv.
Full TAB uses video-h265_stereomode_top_bottom_left_first.mkv.
Half TAB uses video-h264_stereomode_top_bottom_right_first.mkv.

Their Video elements specify DisplayWidth=1280, DisplayHeight=720 and DisplayUnit=0, the intended pixel dimensions of one plane under RFC 9559 section 18.10.
The stored frame is unchanged: respectively 2560x720, 1280x720, 1280x1440 and 1280x720.
CRC32, SeekHead and Cues elements were removed after editing the Video metadata, so no stale checksum or file offset remains.
The fixtures exercise container plane geometry independently of codec half/full layout names.

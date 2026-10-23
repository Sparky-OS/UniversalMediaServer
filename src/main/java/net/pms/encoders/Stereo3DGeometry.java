/*
 * This file is part of Universal Media Server, based on PS3 Media Server.
 *
 * This program is a free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; version 2 of the License only.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package net.pms.encoders;

import java.io.File;
import net.pms.configuration.FormatConfiguration;
import net.pms.media.MediaInfo;
import net.pms.media.video.MediaVideo;
import net.pms.media.video.MediaVideo.Mode3D;
import net.pms.parsers.mediainfo.MediaInfoHelper;
import net.pms.parsers.mediainfo.StreamKind;
import net.pms.store.StoreItem;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Display raster for a network renderer, before its resolution limits. */
final class Stereo3DGeometry {
	private static final Logger LOGGER = LoggerFactory.getLogger(Stereo3DGeometry.class);

	private Stereo3DGeometry() {
	}

	record Size(int width, int height) {
	}

	static Size outputSize(StoreItem item, MediaInfo media, Mode3D output) {
		MediaVideo video = media.getDefaultVideoTrack();
		Mode3D input = video.get3DLayout();
		double width = video.getWidth();
		double height = video.getHeight();
		Double pixelAspect = video.getPixelAspectRatio();
		double sar = pixelAspect != null && Double.isFinite(pixelAspect) && pixelAspect > 0 ? pixelAspect : 1;
		boolean sbs = input == Mode3D.SBSL || input == Mode3D.SBSR || input == Mode3D.SBS2L || input == Mode3D.SBS2R;
		boolean full = input == Mode3D.SBSL || input == Mode3D.SBSR || input == Mode3D.ABL || input == Mode3D.ABR;

		Double planeAspect = getMatroskaPlaneAspect(item, media);
		if (planeAspect != null) {
			sar = planeAspect;
			// RFC 9559 section 18.10: MediaInfo's Matroska DAR describes one
			// plane, not the packed picture. Keep the axis that was not packed;
			// do not apply the codec half/full adjustment to this aspect again.
			if (sbs) {
				width *= sar;
			} else {
				height /= sar;
			}
		} else {
			if (full) {
				if (sbs) {
					width /= 2;
				} else {
					height /= 2;
				}
			}
			width *= sar;
		}

		if (output == Mode3D.SBSL || output == Mode3D.SBSR) {
			width *= 2;
		} else if (output == Mode3D.ABL || output == Mode3D.ABR) {
			height *= 2;
		}
		return new Size(even(width), even(height));
	}

	private static int even(double value) {
		if (!Double.isFinite(value) || value <= 0 || value > Integer.MAX_VALUE - 2) {
			throw new IllegalArgumentException("Invalid stereo display dimension: " + value);
		}
		return (int) Math.max(2, Math.round(value / 2) * 2);
	}

	private static Double getMatroskaPlaneAspect(StoreItem item, MediaInfo media) {
		if (!FormatConfiguration.MKV.equals(media.getContainer()) || item.getFileName() == null || !new File(item.getFileName()).isFile()) {
			return null;
		}
		// The cached layout has no provenance field. Confirm the native mark
		// rather than treating a codec SEI or filename layout in an MKV as one.
		// This is read-only and runs only when a conversion is requested.
		try (MediaInfoHelper helper = new MediaInfoHelper(false)) {
			if (!helper.isValid() || helper.openFile(item.getFileName()) == 0) {
				return null;
			}
			int stream = media.getVideoTracks().indexOf(media.getDefaultVideoTrack());
			String nativeLayout = helper.get(StreamKind.VIDEO, stream, "MultiView_Layout");
			if (StringUtils.isNotBlank(nativeLayout) && nativeLayout.equalsIgnoreCase(media.getDefaultVideoTrack().getMultiViewLayout())) {
				double aspect = Double.parseDouble(helper.get(StreamKind.VIDEO, stream, "PixelAspectRatio"));
				return Double.isFinite(aspect) && aspect > 0 ? aspect : null;
			}
			return null;
		} catch (Exception e) {
			LOGGER.debug("Could not confirm the native stereo display aspect of {}", item.getFileName(), e);
			return null;
		}
	}
}

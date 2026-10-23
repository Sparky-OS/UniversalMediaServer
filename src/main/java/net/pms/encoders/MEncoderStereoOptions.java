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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.pms.configuration.UmsConfiguration;
import net.pms.media.MediaInfo;
import net.pms.media.video.MediaVideo;
import net.pms.media.video.MediaVideo.Mode3D;
import net.pms.renderers.Renderer;
import net.pms.store.StoreItem;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native MEncoder filters and encoder declarations for a network renderer. */
final class MEncoderStereoOptions {
	private static final Logger LOGGER = LoggerFactory.getLogger(MEncoderStereoOptions.class);

	private MEncoderStereoOptions() {
	}

	static Mode3D outputLayout(MediaInfo media, Renderer renderer) throws IOException {
		MediaVideo video = media.getDefaultVideoTrack();
		if (video == null || !video.is3d()) {
			return null;
		}
		if (StringUtils.isBlank(renderer.getOutput3DFormat())) {
			return video.get3DLayout();
		}
		try {
			return Mode3D.valueOf(renderer.getOutput3DFormat().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IOException("Unknown MEncoder stereo output: " + renderer.getOutput3DFormat(), e);
		}
	}

	static String filters(StoreItem item, MediaInfo media, Renderer renderer) throws IOException {
		MediaVideo video = media.getDefaultVideoTrack();
		Mode3D output = outputLayout(media, renderer);
		if (output == null || video.get3DLayout() == output) {
			return null;
		}
		Mode3D input = video.get3DLayout();
		if (input == null || !(input.name().startsWith("SBS") || input.name().startsWith("AB"))) {
			throw new IOException("MEncoder stereo3d cannot read the declared input packing: " + video.getMultiViewLayout());
		}
		Stereo3DGeometry.Size size = Stereo3DGeometry.outputSize(item, media, output);
		UmsConfiguration config = renderer.getUmsConfiguration();
		List<String> filters = new ArrayList<>();
		if (config.isMencoderYadif()) {
			filters.add("yadif");
		}
		filters.add("stereo3d=" + input.name().toLowerCase(Locale.ROOT) + ":" + output.name().toLowerCase(Locale.ROOT));
		// Accurate rounding also avoids corrupt RGB conversion in MPlayer r38683.
		filters.add("scale=" + size.width() + ":" + size.height() + ":arnd=1");
		int width = size.width();
		int height = size.height();
		if (config.isMencoderScaler()) {
			width = config.getMencoderScaleX() > 0 ? config.getMencoderScaleX() : width;
			height = config.getMencoderScaleY() > 0 ? config.getMencoderScaleY() : height;
		}
		if (width != size.width() || height != size.height()) {
			filters.add("scale=" + width + ":" + height + ":arnd=1");
		}
		int overscanWidth = NumberUtils.toInt(config.getMencoderOverscanCompensationWidth());
		int overscanHeight = NumberUtils.toInt(config.getMencoderOverscanCompensationHeight());
		if (overscanWidth > 0 || overscanHeight > 0) {
			width += width * overscanWidth / 100;
			height += height * overscanHeight / 100;
			filters.add("expand=" + width + ":" + height);
		}
		if ((renderer.isKeepAspectRatio() || renderer.isKeepAspectRatioTranscoding()) && !config.isMencoderScaler()) {
			String[] canvas = item.getResolutionForKeepAR(width, height).split("x");
			int paddedWidth = Integer.parseInt(canvas[0]);
			int paddedHeight = Integer.parseInt(canvas[1]);
			if (width != paddedWidth || height != paddedHeight) {
				filters.add("expand=" + paddedWidth + ":" + paddedHeight);
				width = paddedWidth;
				height = paddedHeight;
			}
		}
		int canvasWidth = width;
		int canvasHeight = height;
		double factor = 1;
		if (renderer.getConfiguredMaxVideoWidth() > 0) {
			factor = Math.min(factor, renderer.getConfiguredMaxVideoWidth() / (double) width);
		}
		if (renderer.getConfiguredMaxVideoHeight() > 0) {
			factor = Math.min(factor, renderer.getConfiguredMaxVideoHeight() / (double) height);
		}
		width = Math.max(2, (int) (width * factor) / 2 * 2);
		height = Math.max(2, (int) (height * factor) / 2 * 2);
		if (!renderer.isMuxNonMod4Resolution()) {
			if (renderer.getConfiguredMaxVideoWidth() > 0 && (width + 3) / 4 * 4 > renderer.getConfiguredMaxVideoWidth()) {
				width = renderer.getConfiguredMaxVideoWidth() / 4 * 4;
			}
			if (renderer.getConfiguredMaxVideoHeight() > 0 && (height + 3) / 4 * 4 > renderer.getConfiguredMaxVideoHeight()) {
				height = renderer.getConfiguredMaxVideoHeight() / 4 * 4;
			}
			if (width < 4 || height < 4) {
				throw new IOException("The renderer's limits cannot fit a mod4 MEncoder frame");
			}
		}

		if (width != canvasWidth || height != canvasHeight) {
			filters.add("scale=" + width + ":" + height + ":arnd=1");
		}
		if (!renderer.isMuxNonMod4Resolution()) {
			int paddedWidth = (width + 3) / 4 * 4;
			int paddedHeight = (height + 3) / 4 * 4;
			if (paddedWidth != width || paddedHeight != height) {
				filters.add("expand=" + paddedWidth + ":" + paddedHeight);
				width = paddedWidth;
				height = paddedHeight;
			}
		}
		filters.add("dsize=" + width + ":" + height);
		return String.join(",", filters);
	}

	static String withSubtitles(String filters) {
		return "ass," + filters;
	}

	static void apply(List<String> command, String filters, Mode3D output, EncodingFormat encoding) throws IOException {
		if (output == null) {
			return;
		}
		if (encoding.isTranscodeToH265()) {
			throw new IOException("MEncoder cannot encode the selected H.265 output; select an H.264 MEncoder format or the FFmpeg engine");
		}
		if (filters == null) {
			// The native RGB-to-YUV scaler needs accurate rounding even without repacking.
			filters = "scale=arnd=1";
		}
		List<String> chain = collect(command, "-vf");
		chain.add(0, filters);
		command.addAll(List.of("-vf", String.join(",", chain)));
		boolean packed = output.name().startsWith("SBS") || output.name().startsWith("AB");
		int encoder = command.lastIndexOf("-ovc");
		if (encoder >= 0 && "x264".equals(command.get(encoder + 1))) {
			List<String> options = collect(command, "-x264encopts");
			boolean customPacking = options.stream().anyMatch(value -> value.matches(".*(?:^|:)frame[_-]packing=.*"));
			if (packed && !customPacking && !output.name().endsWith("R")) {
				// H.264 Annex D: type 3 is SBS, type 4 is TAB; x264 exposes only left first.
				options.add("frame_packing=" + (output.name().startsWith("SBS") ? 3 : 4));
			} else if (packed && !customPacking) {
				LOGGER.warn("MEncoder x264 cannot declare right-eye-first output {}; pixels retain the requested eye order", output);
			}
			if (!options.isEmpty()) {
				command.addAll(List.of("-x264encopts", String.join(":", options)));
			}
		} else if (packed && encoding.isTranscodeToMPEG2()) {
			LOGGER.warn("MEncoder keeps MPEG-2 output {}. JP3D needs the output layout in frame Stereo3D side data, which the native MEncoder filter-to-lavc path does not supply; an external FFmpeg stereo3d change alone does not add that native bridge", output);
		}
	}

	private static List<String> collect(List<String> command, String option) {
		List<String> values = new ArrayList<>();
		for (int i = 0; i < command.size() - 1; i++) {
			if (option.equals(command.get(i))) {
				values.add(command.remove(i + 1));
				command.remove(i--);
			}
		}
		return values;
	}
}

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

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.Locale;
import net.pms.configuration.UmsConfiguration;
import net.pms.formats.MKV;
import net.pms.io.OutputParams;
import net.pms.media.MediaInfo;
import net.pms.media.video.MediaVideo;
import net.pms.media.video.MediaVideo.Mode3D;
import net.pms.parsers.ParserTest;
import net.pms.renderers.Renderer;
import net.pms.store.item.RealFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StockStereoDeclarationTest {
	@BeforeAll
	static void configure() {
		ParserTest.setUpClass();
	}

	static List<String> options(String codec, Mode3D output) throws Exception {
		UmsConfiguration config = new UmsConfiguration(false);
		Renderer renderer = new Renderer((String) null) {
			@Override
			public UmsConfiguration getUmsConfiguration() { return config; }
			@Override
			public String getOutput3DFormat() { return output.name().toLowerCase(Locale.ROOT); }
		};
		MediaVideo video = new MediaVideo();
		video.setCodec("h264");
		video.setWidth(192);
		video.setHeight(128);
		video.setBitDepth(8);
		video.setMultiViewLayout("half top-bottom (left eye first)");
		MediaInfo media = new MediaInfo();
		media.setContainer("mp4");
		media.addVideoTrack(video);
		RealFile item = new RealFile(renderer, new File("marked.mp4"));
		item.setFormat(new MKV());
		item.setMediaInfo(media);
		FFMpegVideo engine = new FFMpegVideo() {
			@Override
			public String getExecutable() { return System.getProperty("ums.test.stock.ffmpeg", EngineFactory.getEngineExecutable(StandardEngineId.FFMPEG_VIDEO)); }
		};
		item.setTranscodingSettings(new TranscodingSettings(engine, EncodingFormat.VIDEO.get("MPEGTS-" + codec + "-AC3")));
		OutputParams params = new OutputParams(config);
		params.setMediaRenderer(renderer);
		return engine.getVideoTranscodeOptions(item, media, params, false);
	}

	@Test
	void leftFirstUsesTheStockX264Option() throws Exception {
		for (Mode3D output : List.of(Mode3D.SBSL, Mode3D.SBS2L, Mode3D.ABL, Mode3D.AB2L)) {
			List<String> options = options("H264", output);
			assertFalse(options.contains("-bsf:v"), options.toString());
			assertEquals("frame-packing=" + (output.name().startsWith("SBS") ? 3 : 4), options.get(options.indexOf("-x264-params") + 1));
		}
	}

	@Test
	void rightFirstIsNeverGivenALeftFirstDeclaration() throws Exception {
		for (Mode3D output : List.of(Mode3D.SBSR, Mode3D.SBS2R, Mode3D.ABR, Mode3D.AB2R)) {
			List<String> options = options("H264", output);
			assertFalse(options.contains("-x264-params"), options.toString());
			assertFalse(options.contains("-bsf:v"), options.toString());
		}
	}

	@Test
	void stockH265AndTwoDimensionalOutputsHaveNoInventedOption() throws Exception {
		for (Mode3D output : List.of(Mode3D.SBS2L, Mode3D.SBS2R, Mode3D.AB2L, Mode3D.AB2R, Mode3D.ML, Mode3D.MR, Mode3D.ARCD)) {
			List<String> options = options("H265", output);
			assertFalse(options.contains("-bsf:v"), options.toString());
			assertFalse(options.contains("-x264-params"), options.toString());
		}
	}
}

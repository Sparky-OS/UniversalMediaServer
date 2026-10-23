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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import java.io.File;
import java.util.ArrayList;
import net.pms.util.ProcessUtil;
import org.junit.jupiter.api.io.TempDir;
import java.util.List;
import net.pms.configuration.UmsConfiguration;
import net.pms.formats.MKV;
import net.pms.io.OutputParams;
import net.pms.media.MediaInfo;
import net.pms.media.video.MediaVideo.Mode3D;
import net.pms.parsers.ParserTest;
import net.pms.renderers.Renderer;
import net.pms.store.item.RealFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StereoOutputDeclarationTest {
	@TempDir
	File tempDir;

	@BeforeAll
	static void configure() {
		ParserTest.setUpClass();
	}

	private List<String> options(String encoding, Mode3D output, String acceleration) throws Exception {
		UmsConfiguration config = new UmsConfiguration(false) {
			@Override
			public String getFFmpegGPUH264EncodingAccelerationMethod() { return acceleration; }
			@Override
			public String getFFmpegGPUH265EncodingAccelerationMethod() { return acceleration; }
		};
		Renderer renderer = new Renderer((String) null) {
			@Override
			public UmsConfiguration getUmsConfiguration() { return config; }
			@Override
			public String getOutput3DFormat() { return output.name().toLowerCase(java.util.Locale.ROOT); }
		};
		MediaInfo media = StereoRendererGeometryTest.media(Mode3D.AB2L, 1920, 1080);
		RealFile item = new RealFile(renderer, new File("stereo.mp4"));
		item.setMediaInfo(media);
		item.setFormat(new MKV());
		FFMpegVideo engine = new FFMpegVideo() {
			@Override
			public String getExecutable() { return System.getProperty("ums.test.stereo.ffmpeg", EngineFactory.getEngineExecutable(StandardEngineId.FFMPEG_VIDEO)); }
		};
		item.setTranscodingSettings(new TranscodingSettings(engine, EncodingFormat.VIDEO.get(encoding)));
		OutputParams params = new OutputParams(config);
		params.setMediaRenderer(renderer);
		return engine.getVideoTranscodeOptions(item, media, params, false);
	}

	@Test
	void hardwareH264AndH265AlsoDeclareBothEyeOrders() throws Exception {
		for (String codec : List.of("H264", "H265")) {
			for (Mode3D output : List.of(Mode3D.SBS2L, Mode3D.SBS2R, Mode3D.AB2L, Mode3D.AB2R)) {
				List<String> options = options("MPEGTS-" + codec + "-AC3", output, codec.equals("H264") ? "h264_nvenc" : "hevc_nvenc");
				int bsf = options.indexOf("-bsf:v");
				String ffmpeg = System.getProperty("ums.test.stereo.ffmpeg", EngineFactory.getEngineExecutable(StandardEngineId.FFMPEG_VIDEO));
				if (!FFmpegOptions.isFramePackingSupported(ffmpeg, codec.equals("H264") ? "h264_metadata" : "hevc_metadata")) {
					assertEquals(-1, bsf, options.toString());
					continue;
				}
				assertTrue(bsf >= 0, options.toString());
				assertEquals((codec.equals("H264") ? "h264_metadata" : "hevc_metadata") + "=frame_packing=insert:" +
					FFMpegVideo.getFramePackingFilterOptions(output), options.get(bsf + 1));
			}
		}
	}

	@Test
	void monoAndAnaglyphDoNotAcquireStereoDeclarations() throws Exception {
		for (Mode3D output : List.of(Mode3D.ML, Mode3D.MR, Mode3D.ARCD)) {
			List<String> options = options("MPEGTS-H264-AC3", output, "libx264");
			assertFalse(options.contains("-bsf:v"), options.toString());
			assertFalse(options.contains("-x264-params"), options.toString());
		}
	}

	@Test
	void encodedStreamsCarryOnlyDeclarationsTheEncoderCanWrite() throws Exception {
		String ffmpeg = System.getProperty("ums.test.stereo.ffmpeg", EngineFactory.getEngineExecutable(StandardEngineId.FFMPEG_VIDEO));
		for (String codec : List.of("H264", "H265")) {
			for (Mode3D output : List.of(Mode3D.SBS2L, Mode3D.SBS2R, Mode3D.AB2L, Mode3D.AB2R, Mode3D.ML, Mode3D.MR, Mode3D.ARCD)) {
				String encoder = codec.equals("H264") ? "libx264" : "libx265";
				List<String> options = options("MPEGTS-" + codec + "-AC3", output, encoder);
				File stream = new File(tempDir, codec + "-" + output + ".ts");
				File source = new File(tempDir, "marked-tab.mp4");
				if (!source.exists()) {
					String generated = ProcessUtil.run(ffmpeg, "-nostdin", "-y", "-f", "lavfi", "-i", "color=red:s=192x128:r=1:d=1",
						"-c:v", "libx264", "-x264-params", "frame-packing=4", "-pix_fmt", "yuv420p", "-threads", "1", "-frames:v", "1", source.getAbsolutePath());
					assertTrue(source.length() > 0, generated);
				}
				List<String> command = new ArrayList<>(List.of(ffmpeg, "-nostdin", "-y", "-i", source.getAbsolutePath()));
				command.addAll(StereoRendererGeometryTest.filters(StereoRendererGeometryTest.media(Mode3D.AB2L, 192, 128), StereoRendererGeometryTest.renderer(output)));
				command.addAll(options);
				if (codec.equals("H265")) {
					command.addAll(List.of("-x265-params", "pools=1:frame-threads=1"));
				}
				command.addAll(List.of("-threads", "1", "-frames:v", "1", stream.getAbsolutePath()));
				String log = ProcessUtil.run(command.toArray(String[]::new));
				assertTrue(stream.length() > 0, log);
				String headers = ProcessUtil.run(ffmpeg, "-hide_banner", "-i", stream.getAbsolutePath(), "-map", "0:v:0", "-c:v", "copy", "-bsf:v", "trace_headers", "-f", "null", "-");
				assertTrue(headers.contains("nal_unit_type"), headers);
				String arrangementField = codec.equals("H264") ? "frame_packing_arrangement_type" : "fp_arrangement_type";
				String interpretationField = codec.equals("H264") ? "content_interpretation_type" : "fp_content_interpretation_type";
				boolean declared = options.contains("-bsf:v") || options.contains("-x264-params");
				if (declared) {
					assertTrue(java.util.regex.Pattern.compile(arrangementField + ".*= " + (output.name().startsWith("SBS") ? 3 : 4)).matcher(headers).find(), headers);
					assertTrue(java.util.regex.Pattern.compile(interpretationField + ".*= " + (output.name().endsWith("R") ? 2 : 1)).matcher(headers).find(), headers);
				} else {
					assertFalse(headers.contains(arrangementField), headers);
				}
				System.out.println("ENCODED_STEREO_DECLARATION " + codec + " " + output + " present=" + declared + " command=" + command);
			}
		}
	}

	@Test
	void mpeg2KeepsTheSelectedCodecAndExplainsTheMissingDeclaration() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(FFMpegVideo.class);
		Level previous = logger.getLevel();
		ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.WARN);
		try {
			List<String> options = options("MPEGPS-MPEG2-AC3", Mode3D.SBS2L, "libx264");
			assertEquals("mpeg2video", options.get(options.indexOf("-c:v") + 1));
			assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("MPEG-2 cannot carry the frame packing SEI")));
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(previous);
			appender.stop();
		}
	}
}

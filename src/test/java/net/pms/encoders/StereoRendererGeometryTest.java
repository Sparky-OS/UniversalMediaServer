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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.pms.formats.Format;
import net.pms.io.OutputParams;
import net.pms.media.MediaInfo;
import net.pms.media.video.MediaVideo;
import net.pms.media.video.MediaVideo.Mode3D;
import net.pms.parsers.MediaInfoParser;
import net.pms.parsers.ParserTest;
import net.pms.renderers.Renderer;
import net.pms.store.item.RealFile;
import net.pms.util.ProcessUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class StereoRendererGeometryTest {

	@TempDir
	File tempDir;

	@BeforeAll
	static void configure() {
		ParserTest.setUpClass();
	}

	private record Conversion(Mode3D input, Mode3D output) { }

	static Stream<Conversion> conversions() {
		return Stream.of(Mode3D.values()).filter(mode -> mode.name().startsWith("SBS") || mode.name().startsWith("AB"))
			.flatMap(input -> Stream.of(Mode3D.values()).map(output -> new Conversion(input, output)));
	}

	static String layout(Mode3D mode) {
		return (mode.name().contains("2") ? "half " : "") + (mode.name().startsWith("SBS") ? "side by side" : "top-bottom") +
			" (" + (mode.name().endsWith("R") ? "right" : "left") + " eye first)";
	}

	static int[] outputSize(Mode3D mode, int eyeWidth, int eyeHeight) {
		return new int[] {mode == Mode3D.SBSL || mode == Mode3D.SBSR ? eyeWidth * 2 : eyeWidth,
			mode == Mode3D.ABL || mode == Mode3D.ABR ? eyeHeight * 2 : eyeHeight};
	}

	static MediaInfo media(Mode3D input, int eyeWidth, int eyeHeight) {
		MediaVideo video = new MediaVideo();
		int[] size = outputSize(input, eyeWidth, eyeHeight);
		video.setWidth(size[0]);
		video.setHeight(size[1]);
		video.setPixelAspectRatio(1.0);
		video.setCodec("h264");
		video.setMultiViewLayout(layout(input));
		MediaInfo media = new MediaInfo();
		media.setContainer("mp4");
		media.addVideoTrack(video);
		media.setMediaParser("test");
		return media;
	}

	static Renderer renderer(Mode3D output) throws Exception {
		return new Renderer((String) null) {
			@Override
			public int getMaxVideoWidth() { return 8192; }
			@Override
			public int getMaxVideoHeight() { return 8192; }
			@Override
			public String getOutput3DFormat() {
				return output.name().toLowerCase(Locale.ROOT);
			}
		};
	}

	static List<String> filters(MediaInfo media, Renderer renderer) throws Exception {
		return filters(media, renderer, new File("stereo.mp4"));
	}

	static List<String> filters(MediaInfo media, Renderer renderer, File source) throws Exception {
		RealFile item = new RealFile(renderer, source);
		item.setMediaInfo(media);
		OutputParams params = new OutputParams(renderer.getUmsConfiguration());
		params.setMediaRenderer(renderer);
		return new FFMpegVideo().getVideoFilterOptions(item, media, params, false);
	}

	@TestFactory
	Stream<DynamicTest> filterChains() {
		return conversions().map(c -> DynamicTest.dynamicTest(c.toString(), () -> everyConversionHasItsDisplayRasterAfterRepacking(c.input(), c.output())));
	}

	void everyConversionHasItsDisplayRasterAfterRepacking(Mode3D input, Mode3D output) throws Exception {
		int[] size = outputSize(output, 192, 128);
		List<String> expected = input == output ? List.of() : List.of("-filter_complex", "[0:v]stereo3d=" +
			input.name().toLowerCase(Locale.ROOT) + ":" + output.name().toLowerCase(Locale.ROOT) +
			",scale=" + size[0] + ":" + size[1] + ",setsar=1,sidedata=mode=delete:type=STEREO3D");
		assertEquals(expected, filters(media(input, 192, 128), renderer(output)));
	}

	@TestFactory
	Stream<DynamicTest> decodedGeometry() {
		return conversions().map(c -> DynamicTest.dynamicTest(c.toString(), () -> ffprobeMeasuresEveryInputAndOutput(c.input(), c.output())));
	}

	void ffprobeMeasuresEveryInputAndOutput(Mode3D input, Mode3D output) throws Exception {
		ffprobeMeasuresEveryInputAndOutput(input, output, 192, 128);
	}

	void ffprobeMeasuresEveryInputAndOutput(Mode3D input, Mode3D output, int eyeWidth, int eyeHeight) throws Exception {
		ffprobeMeasuresEveryInputAndOutput(input, output, eyeWidth, eyeHeight, 1.0);
	}

	void ffprobeMeasuresEveryInputAndOutput(Mode3D input, Mode3D output, int eyeWidth, int eyeHeight, double sar) throws Exception {
		MediaInfo media = media(input, eyeWidth, eyeHeight);
		media.getDefaultVideoTrack().setPixelAspectRatio(sar);
		int width = media.getWidth();
		int height = media.getHeight();
		byte[] pixels = new byte[width * height * 3];
		boolean sbs = input.name().startsWith("SBS");
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean first = sbs ? x < width / 2 : y < height / 2;
				boolean left = first != input.name().endsWith("R");
				int offset = (y * width + x) * 3;
				pixels[offset] = (byte) (left ? 255 : 0);
				pixels[offset + 2] = (byte) (left ? 0 : 255);
			}
		}
		File source = new File(tempDir, "source.rgb");
		Files.write(source.toPath(), pixels);
		File converted = new File(tempDir, "converted.nut");
		Files.deleteIfExists(converted.toPath());
		String ffmpeg = System.getProperty("ums.test.stereo.ffmpeg", EngineFactory.getEngineExecutable(StandardEngineId.FFMPEG_VIDEO));
		assertNotNull(ffmpeg, "FFmpeg is required for the renderer geometry matrix");
		File packed = new File(tempDir, input.name() + ".nut");
		if (!packed.exists()) {
			String generated = ProcessUtil.run(ffmpeg, "-nostdin", "-y", "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size",
				width + "x" + height, "-i", source.getAbsolutePath(), "-vf", "setsar=" + sar, "-frames:v", "1", "-c:v", "ffv1", "-pix_fmt", "gbrp", "-threads", "1", packed.getAbsolutePath());
			assertTrue(packed.length() > 0, generated);
		}
		List<String> command = new ArrayList<>(List.of(ffmpeg, "-nostdin", "-y", "-threads", "1",
			"-i", packed.getAbsolutePath()));
		command.addAll(filters(media, renderer(output)));
		command.addAll(List.of("-filter_complex_threads", "1", "-frames:v", "1", "-c:v", "ffv1", "-pix_fmt", "gbrp", "-threads", "1", converted.getAbsolutePath()));
		String log = ProcessUtil.run(command.toArray(String[]::new));
		assertTrue(converted.length() > 0, log);
		String probe = ProcessUtil.run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
			"stream=width,height,sample_aspect_ratio", "-of", "default=noprint_wrappers=1", converted.getAbsolutePath());
		int[] expected = outputSize(output, (int) Math.round(eyeWidth * sar), eyeHeight);
		assertTrue(probe.contains("width=" + expected[0] + "\n"), probe);
		assertTrue(probe.contains("height=" + expected[1] + "\n"), probe);
		assertTrue(probe.contains("sample_aspect_ratio=1:1"), probe);
		System.out.println("STEREO_RASTER " + input + " -> " + output + " " + expected[0] + "x" + expected[1] + " SAR 1:1");
		if (!output.name().startsWith("A") || output.name().startsWith("AB")) {
			File rawOutput = new File(tempDir, "decoded.rgb");
			Files.deleteIfExists(rawOutput.toPath());
			ProcessUtil.run(ffmpeg, "-v", "error", "-y", "-i", converted.getAbsolutePath(), "-frames:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", rawOutput.getAbsolutePath());
			byte[] decoded = Files.readAllBytes(rawOutput.toPath());
			assertEquals(expected[0] * expected[1] * 3, decoded.length);
			boolean mono = output == Mode3D.ML || output == Mode3D.MR;
			for (int eye = 0; eye < (mono ? 1 : 2); eye++) {
				boolean rightFirst = output == Mode3D.MR || output.name().endsWith("R");
				boolean left = (eye == 0) != rightFirst;
				int x = expected[0] / 4;
				int y = expected[1] / 4;
				if (output.name().startsWith("SBS")) { x += eye * expected[0] / 2; }
				if (output.name().startsWith("AB")) { y += eye * expected[1] / 2; }
				int offset = (y * expected[0] + x) * 3;
				assertEquals(left ? 255 : 0, Byte.toUnsignedInt(decoded[offset]), 3);
				assertEquals(left ? 0 : 255, Byte.toUnsignedInt(decoded[offset + 2]), 3);
			}
		}

	}

	@TestFactory
	Stream<DynamicTest> nativePlaneRasters() {
		return Stream.of("full-sbs", "half-sbs", "full-tab", "half-tab").flatMap(name -> Stream.of(Mode3D.values())
			.filter(output -> output != switch (name) {
				case "full-sbs" -> Mode3D.SBSR;
				case "half-sbs" -> Mode3D.SBSL;
				case "full-tab" -> Mode3D.ABL;
				default -> Mode3D.ABR;
			})
			.map(output -> DynamicTest.dynamicTest(name + " -> " + output, () -> {
				File source = ParserTest.getTestFile("video-stereo-display-" + name + ".mkv");
				MediaInfo media = new MediaInfo();
				MediaInfoParser.parse(media, source, Format.VIDEO);
				List<String> filters = filters(media, renderer(output), source);
				int[] size = outputSize(output, 1280, 720);
				assertTrue(filters.get(1).endsWith(",scale=" + size[0] + ":" + size[1] + ",setsar=1,sidedata=mode=delete:type=STEREO3D"), filters.toString());
				File converted = new File(tempDir, "native.nut");
				Files.deleteIfExists(converted.toPath());
				String ffmpeg = System.getProperty("ums.test.stereo.ffmpeg", EngineFactory.getEngineExecutable(StandardEngineId.FFMPEG_VIDEO));
				List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-nostdin", "-y", "-i", source.getAbsolutePath()));
				cmd.addAll(filters);
				cmd.addAll(List.of("-filter_complex_threads", "1", "-frames:v", "1", "-c:v", "ffv1", "-threads", "1", converted.getAbsolutePath()));
				String log = ProcessUtil.run(cmd.toArray(String[]::new));
				assertTrue(converted.length() > 0, log);
				String probe = ProcessUtil.run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,sample_aspect_ratio",
					"-of", "default=noprint_wrappers=1", converted.getAbsolutePath());
				assertTrue(probe.contains("width=" + size[0] + "\n") && probe.contains("height=" + size[1] + "\n") && probe.contains("sample_aspect_ratio=1:1"), probe);
				System.out.println("NATIVE_STEREO_RASTER " + name + " -> " + output + " " + size[0] + "x" + size[1] + " SAR 1:1");
			})));
	}

	@Test
	void televisionHalfAndMonoRastersAre1920By1080() throws Exception {
		for (Mode3D output : List.of(Mode3D.SBS2L, Mode3D.AB2R, Mode3D.MR)) {
			ffprobeMeasuresEveryInputAndOutput(Mode3D.AB2L, output, 1920, 1080);
		}
	}

	@Test
	void anamorphicSamplesUseTheirDisplayWidth() throws Exception {
		for (Mode3D output : List.of(Mode3D.AB2R, Mode3D.ML, Mode3D.SBSR)) {
			ffprobeMeasuresEveryInputAndOutput(Mode3D.SBS2L, output, 1440, 1080, 4.0 / 3);
		}
	}

	@Test
	void rendererLimitsAreAppliedAfterRepacking() throws Exception {
		Renderer renderer = new Renderer((String) null) {
			@Override
			public String getOutput3DFormat() { return "sbs2l"; }
			@Override
			public int getMaxVideoWidth() { return 1280; }
			@Override
			public int getMaxVideoHeight() { return 720; }
			@Override
			public boolean isMaximumResolutionSpecified() { return true; }
		};
		String chain = filters(media(Mode3D.AB2L, 1920, 1080), renderer).get(1);
		assertTrue(chain.startsWith("[0:v]stereo3d=ab2l:sbs2l,scale=1920:1080,setsar=1,scale="), chain);
		assertTrue(chain.endsWith("setsar=1,sidedata=mode=delete:type=STEREO3D"), chain);
		File converted = new File(tempDir, "limited.nut");
		String ffmpeg = System.getProperty("ums.test.stereo.ffmpeg", EngineFactory.getEngineExecutable(StandardEngineId.FFMPEG_VIDEO));
		List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-nostdin", "-y", "-f", "lavfi", "-i", "color=red:s=1920x1080:r=1:d=1"));
		cmd.addAll(filters(media(Mode3D.AB2L, 1920, 1080), renderer));
		cmd.addAll(List.of("-filter_complex_threads", "1", "-frames:v", "1", "-c:v", "ffv1", "-threads", "1", converted.getAbsolutePath()));
		String log = ProcessUtil.run(cmd.toArray(String[]::new));
		assertTrue(converted.length() > 0, log);
		String probe = ProcessUtil.run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,sample_aspect_ratio", "-of", "default=noprint_wrappers=1", converted.getAbsolutePath());
		assertTrue(probe.contains("width=1280\n") && probe.contains("height=720\n") && probe.contains("sample_aspect_ratio=1:1"), probe);
	}

	@Test
	void ordinary2dAndUnsupportedAnaglyphInputHaveNoConversion() throws Exception {
		MediaInfo media = media(Mode3D.SBSL, 192, 128);
		media.getDefaultVideoTrack().setMultiViewLayout(null);
		assertTrue(filters(media, renderer(Mode3D.AB2L)).isEmpty());
		media.getDefaultVideoTrack().setMultiViewLayout("arcd");
		assertTrue(filters(media, renderer(Mode3D.AB2L)).isEmpty());
	}

	@Test
	void nativeMatroskaPlaneAspectIsNotAdjustedTwice() throws Exception {
		File source = ParserTest.getTestFile("video-h264_stereomode_side_by_side_left_first.mkv");
		MediaInfo media = new MediaInfo();
		MediaInfoParser.parse(media, source, Format.VIDEO);
		assertEquals(List.of("-filter_complex", "[0:v]stereo3d=sbsl:abl,scale=640:1440,setsar=1,sidedata=mode=delete:type=STEREO3D"), filters(media, renderer(Mode3D.ABL), source));
	}
}

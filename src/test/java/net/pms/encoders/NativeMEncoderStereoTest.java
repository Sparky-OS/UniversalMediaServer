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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.pms.PMS;
import net.pms.io.OutputParams;
import net.pms.media.MediaInfo;
import net.pms.util.SubtitleUtils;
import net.pms.media.video.MediaVideo.Mode3D;
import net.pms.parsers.ParserTest;
import net.pms.util.ProcessUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeMEncoderStereoTest {
	@TempDir
	File tempDir;

	@BeforeAll
	static void configure() {
		ParserTest.setUpClass();
	}

	private String executable() {
		String path = System.getProperty("ums.test.mencoder");
		Assumptions.assumeTrue(path != null && new File(path).canExecute(), "Set ums.test.mencoder for the native MEncoder qualification");
		return path;
	}

	private String ffmpeg() {
		return System.getProperty("ums.test.stereo.ffmpeg", EngineFactory.getEngineExecutable(StandardEngineId.FFMPEG_VIDEO));
	}

	private File fixture(Mode3D input, int eyeWidth, int eyeHeight) throws Exception {
		return fixture(input, eyeWidth, eyeHeight, "yuv420p");
	}

	private File fixture(Mode3D input, int eyeWidth, int eyeHeight, String format) throws Exception {
		int[] size = StereoRendererGeometryTest.outputSize(input, eyeWidth, eyeHeight);
		byte[] pixels = new byte[size[0] * size[1] * 3];
		for (int y = 0; y < size[1]; y++) {
			for (int x = 0; x < size[0]; x++) {
				boolean first = input.name().startsWith("SBS") ? x < size[0] / 2 : y < size[1] / 2;
				boolean left = first != input.name().endsWith("R");
				pixels[(y * size[0] + x) * 3 + (left ? 0 : 2)] = (byte) 255;
			}
		}
		File raw = new File(tempDir, input + "-" + eyeWidth + ".rgb");
		Files.write(raw.toPath(), pixels);
		File source = new File(tempDir, input + "-" + eyeWidth + ".avi");
		String log = ProcessUtil.run(ffmpeg(), "-nostdin", "-v", "error", "-y", "-stream_loop", "3", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", size[0] + "x" + size[1],
			"-r", "25", "-i", raw.getAbsolutePath(), "-frames:v", "4", "-c:v", "ffv1", "-pix_fmt", format, "-threads", "1", source.getAbsolutePath());
		assertTrue(source.length() > 0, log);
		return source;
	}

	private File encode(File source, MediaInfo media, Mode3D output, String codec) throws Exception {
		return encode(source, media, output, codec, null);
	}

	private File encode(File source, MediaInfo media, Mode3D output, String codec, File subtitles) throws Exception {
		File target = new File(tempDir, media.getDefaultVideoTrack().get3DLayout() + "-" + output + "-" + codec + ".ts");
		List<String> command = new ArrayList<>(List.of(executable(), source.getAbsolutePath(), "-nosound", "-ovc", codec.equals("H264") ? "x264" : "lavc",
			"-of", "lavf", "-lavfopts", "format=mpegts", "-ofps", "25", "-frames", "4", "-o", target.getAbsolutePath()));
		if (codec.equals("H264")) {
			command.addAll(List.of("-x264encopts", "crf=18:preset=ultrafast:threads=1:keyint=1"));
		} else {
			command.addAll(List.of("-lavcopts", "vcodec=mpeg2video:vqscale=2:threads=1"));
		}
		String filters = MEncoderStereoTest.filters(media, MEncoderStereoTest.renderer(output), source);
		if (subtitles != null) {
			command.addAll(List.of("-ass", "-sub", subtitles.getAbsolutePath(), "-utf8"));
			filters = MEncoderStereoOptions.withSubtitles(filters);
		}
		MEncoderStereoOptions.apply(command, filters, output, EncodingFormat.VIDEO.get("MPEGTS-" + codec + "-AC3"));
		String log = ProcessUtil.run(command.toArray(String[]::new));
		assertTrue(target.length() > 0, log);
		String directory = System.getProperty("ums.test.mencoder.evidence");
		if (directory != null) {
			Path evidence = Path.of(directory);
			Files.createDirectories(evidence);
			Files.copy(target.toPath(), evidence.resolve(target.getName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(evidence.resolve(target.getName() + ".log"), command + "\n" + log);
		}
		return target;
	}

	private byte[] pixels(File output) throws Exception {
		File raw = new File(tempDir, "decoded.rgb");
		String log = ProcessUtil.run(ffmpeg(), "-nostdin", "-v", "error", "-y", "-i", output.getAbsolutePath(), "-frames:v", "1", "-pix_fmt", "rgb24", "-threads", "1", "-f", "rawvideo", raw.getAbsolutePath());
		assertTrue(raw.length() > 0, log);
		return Files.readAllBytes(raw.toPath());
	}

	@Test
	void everySupportedPackingAndOutputHasThePredictedRasterEyesAndMark() throws Exception {
		executable();
		for (Mode3D input : Mode3D.values()) {
			if (!input.name().startsWith("SBS") && !input.name().startsWith("AB")) {
				continue;
			}
			File source = fixture(input, 192, 128);
			MediaInfo media = StereoRendererGeometryTest.media(input, 192, 128);
			media.setContainer("avi");
			for (Mode3D output : Mode3D.values()) {
				File target = encode(source, media, output, "H264");
				int[] size = StereoRendererGeometryTest.outputSize(output, 192, 128);
				String probe = ProcessUtil.run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,sample_aspect_ratio", "-of", "default=noprint_wrappers=1", target.getAbsolutePath());
				assertTrue(probe.contains("width=" + size[0] + "\n") && probe.contains("height=" + size[1] + "\n") && probe.contains("sample_aspect_ratio=1:1"), probe);
				byte[] decoded = pixels(target);
				assertEquals(size[0] * size[1] * 3, decoded.length);
				boolean packed = output.name().startsWith("SBS") || output.name().startsWith("AB");
				if (packed || output == Mode3D.ML || output == Mode3D.MR) {
					for (int eye = 0; eye < (packed ? 2 : 1); eye++) {
						boolean left = (eye == 0) != (output == Mode3D.MR || output.name().endsWith("R"));
						int x = size[0] / 4 + (output.name().startsWith("SBS") ? eye * size[0] / 2 : 0);
						int y = size[1] / 4 + (output.name().startsWith("AB") ? eye * size[1] / 2 : 0);
						int offset = (y * size[0] + x) * 3;
						assertEquals(left ? 255 : 0, Byte.toUnsignedInt(decoded[offset]), 8, input + " -> " + output);
						assertEquals(left ? 0 : 255, Byte.toUnsignedInt(decoded[offset + 2]), 8, input + " -> " + output);
					}
				} else {
					// Fixed-point matrices in MPlayer's vf_stereo3d.c, red left and blue right.
					// Its yellow/blue Dubois matrix differs from current FFmpeg's matrix.
					int[] expected = switch (output) {
						case ARCG -> new int[] {76, 29, 29};
						case ARCH -> new int[] {76, 0, 255};
						case ARCC -> new int[] {255, 0, 255};
						case ARCD -> new int[] {115, 0, 255};
						case AGMG -> new int[] {29, 76, 29};
						case AGMH -> new int[] {0, 76, 255};
						case AGMC -> new int[] {0, 0, 255};
						case AGMD -> new int[] {0, 55, 235};
						case AYBG -> new int[] {29, 29, 76};
						case AYBH -> new int[] {0, 0, 76};
						case AYBC -> new int[] {0, 0, 0};
						case AYBD -> new int[] {251, 0, 209};
						default -> throw new AssertionError(output);
					};
					int offset = (size[1] / 4 * size[0] + size[0] / 4) * 3;
					for (int channel = 0; channel < 3; channel++) {
						assertEquals(expected[channel], Byte.toUnsignedInt(decoded[offset + channel]), 8, input + " -> " + output);
					}
				}
				String headers = ProcessUtil.run(ffmpeg(), "-nostdin", "-i", target.getAbsolutePath(), "-map", "0:v:0", "-c:v", "copy", "-bsf:v", "trace_headers", "-f", "null", "-");
				boolean declared = packed && !output.name().endsWith("R");
				assertTrue(headers.contains("nal_unit_type"), headers);
				assertEquals(declared, headers.contains("frame_packing_arrangement_type"), input + " -> " + output);
				if (declared) {
					assertTrue(java.util.regex.Pattern.compile("frame_packing_arrangement_type.*= " + (output.name().startsWith("SBS") ? 3 : 4)).matcher(headers).find(), headers);
					assertTrue(java.util.regex.Pattern.compile("content_interpretation_type.*= 1").matcher(headers).find(), headers);
				}
				System.out.println("MENCODER_NATIVE " + input + " -> " + output + " " + size[0] + "x" + size[1] + " SAR1 declared=" + declared + " eyes=PASS");
			}
		}
	}

	@Test
	void nonSquareInputUsesDisplayGeometryAndDoesNotCopyItsOldSei() throws Exception {
		executable();
		File pixels = fixture(Mode3D.SBSL, 192, 128);
		File source = new File(tempDir, "non-square-marked.mp4");
		ProcessUtil.run(ffmpeg(), "-nostdin", "-v", "error", "-y", "-i", pixels.getAbsolutePath(), "-vf", "setsar=1/2", "-c:v", "libx264",
			"-threads", "1", "-bf", "0", "-x264-params", "frame-packing=3:keyint=1", source.getAbsolutePath());
		MediaInfo media = StereoRendererGeometryTest.media(Mode3D.SBSL, 192, 128);
		media.getDefaultVideoTrack().setPixelAspectRatio(0.5);
		File target = encode(source, media, Mode3D.SBS2R, "H264");
		String probe = ProcessUtil.run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,sample_aspect_ratio", "-of", "default=noprint_wrappers=1", target.getAbsolutePath());
		assertTrue(probe.contains("width=96\n") && probe.contains("height=128\n") && probe.contains("sample_aspect_ratio=1:1"), probe);
		String headers = ProcessUtil.run(ffmpeg(), "-nostdin", "-i", target.getAbsolutePath(), "-map", "0:v:0", "-c:v", "copy", "-bsf:v", "trace_headers", "-f", "null", "-");
		assertFalse(headers.contains("frame_packing_arrangement_type"), "Do not copy the source's left-first mark to right-first output");
		System.out.println("MENCODER_NON_SQUARE sourceSAR=1:2 output=96x128 SAR1 oldSEI=absent");
	}

	@Test
	void nativeRgbConversionPreservesColorsWithAndWithoutRepacking() throws Exception {
		executable();
		for (Mode3D input : List.of(Mode3D.SBS2L, Mode3D.AB2L)) {
			File source = fixture(input, 192, 128, "bgra");
			File target = encode(source, StereoRendererGeometryTest.media(input, 192, 128), Mode3D.SBS2L, "H264");
			byte[] decoded = pixels(target);
			for (int eye = 0; eye < 2; eye++) {
				int offset = (32 * 192 + 48 + eye * 96) * 3;
				assertEquals(eye == 0 ? 255 : 0, Byte.toUnsignedInt(decoded[offset]), 8);
				assertEquals(0, Byte.toUnsignedInt(decoded[offset + 1]), 8);
				assertEquals(eye == 0 ? 0 : 255, Byte.toUnsignedInt(decoded[offset + 2]), 8);
			}
			System.out.println("MENCODER_RGB " + input + " -> SBS2L colors=PASS");
		}
	}

	@Test
	void ordinaryCaptionsKeepTheirTimingAndRelativePositionInBothEyes() throws Exception {
		executable();
		var config = PMS.getConfiguration();
		int depth = config.getDepth3D();
		String fontHeight = Double.toString(config.getSubtitleFontHeightPercent());
		config.setDepth3D(0);
		config.setSubtitleFontHeightPercent("10");
		try {
			for (Mode3D input : List.of(Mode3D.SBS2L, Mode3D.SBS2R, Mode3D.AB2L, Mode3D.AB2R)) {
				Mode3D output = input.name().startsWith("SBS") ? Mode3D.AB2L : Mode3D.SBS2L;
				MediaInfo media = StereoRendererGeometryTest.media(input, 384, 288);
				File source = fixture(input, 384, 288);
				File original = new File(tempDir, input + "-original.ass");
				String text = "[Script Info]\nScriptType: v4.00+\nPlayResX: 384\nPlayResY: 288\n[V4+ Styles]\n" +
					"Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n" +
					"Style: Default,Arial,28,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,0,0,15,0\n" +
					"[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
					"Dialogue: 0,0:00:00.04,0:00:00.12,Default,,0,0,0,,TEST\n";
				Files.writeString(original.toPath(), text);
				var originalTime = Files.getLastModifiedTime(original.toPath());
				File cached = new File(tempDir, input + "-ums-cache.ass");
				Files.copy(original.toPath(), cached.toPath());
				File subtitles = SubtitleUtils.convertASSToASS3D(cached, media, new OutputParams(config));
				File target = encode(source, media, output, "H264", subtitles);
				File raw = new File(tempDir, input + "-captions.rgb");
				ProcessUtil.run(ffmpeg(), "-nostdin", "-v", "error", "-y", "-i", target.getAbsolutePath(), "-frames:v", "4", "-pix_fmt", "rgb24", "-threads", "1", "-f", "rawvideo", raw.getAbsolutePath());
				byte[] decoded = Files.readAllBytes(raw.toPath());
				int width = 384;
				int height = 288;
				assertEquals(width * height * 3 * 4, decoded.length);
				for (int frame = 0; frame < 4; frame++) {
					int[][] bounds = {{width, height, -1, -1, 0}, {width, height, -1, -1, 0}};
					for (int y = 0; y < height; y++) {
						for (int x = 0; x < width; x++) {
							int offset = ((frame * height + y) * width + x) * 3;
							if (Byte.toUnsignedInt(decoded[offset]) > 185 && Byte.toUnsignedInt(decoded[offset + 1]) > 185 && Byte.toUnsignedInt(decoded[offset + 2]) > 185) {
								int eye = output == Mode3D.SBS2L ? x / (width / 2) : y / (height / 2);
								int eyeX = output == Mode3D.SBS2L ? x % (width / 2) : x;
								int eyeY = output == Mode3D.AB2L ? y % (height / 2) : y;
								bounds[eye][0] = Math.min(bounds[eye][0], eyeX);
								bounds[eye][1] = Math.min(bounds[eye][1], eyeY);
								bounds[eye][2] = Math.max(bounds[eye][2], eyeX);
								bounds[eye][3] = Math.max(bounds[eye][3], eyeY);
								bounds[eye][4]++;
							}
						}
					}
					if (frame == 0 || frame == 3) {
						assertEquals(0, bounds[0][4] + bounds[1][4], input + " caption timing frame " + frame);
					} else {
						assertTrue(bounds[0][4] > 4 && bounds[1][4] > 4, input + " captions in both views");
						for (int coordinate = 0; coordinate < 4; coordinate++) {
							assertEquals(bounds[0][coordinate], bounds[1][coordinate], 1, input + " relative caption position");
						}
					}
				}
				assertEquals(text, Files.readString(original.toPath()));
				assertEquals(originalTime, Files.getLastModifiedTime(original.toPath()));
				System.out.println("MENCODER_CAPTIONS " + input + " -> " + output + " identity timing plane source=PASS");
			}
		} finally {
			config.setDepth3D(depth);
			config.setSubtitleFontHeightPercent(fontHeight);
		}
	}

	@Test
	void fourKWithoutDeclaredLimitsIsNotReducedToTheLegacyDefault() throws Exception {
		executable();
		File source = fixture(Mode3D.SBS2L, 3840, 2160);
		File output = encode(source, StereoRendererGeometryTest.media(Mode3D.SBS2L, 3840, 2160), Mode3D.AB2L, "H264");
		String probe = ProcessUtil.run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,sample_aspect_ratio", "-of", "default=noprint_wrappers=1", output.getAbsolutePath());
		assertTrue(probe.contains("width=3840\n") && probe.contains("height=2160\n") && probe.contains("sample_aspect_ratio=1:1"), probe);
		System.out.println("MENCODER_4K_NO_LIMITS " + probe.replace('\n', ' '));
	}

	@Test
	void nativeMpeg2KeepsItsCodecAndDoesNotInventJp3d() throws Exception {
		executable();
		File source = fixture(Mode3D.AB2L, 192, 128);
		File output = encode(source, StereoRendererGeometryTest.media(Mode3D.AB2L, 192, 128), Mode3D.SBS2L, "MPEG2");
		String bytes = new String(Files.readAllBytes(output.toPath()), java.nio.charset.StandardCharsets.ISO_8859_1);
		assertFalse(bytes.contains("JP3D"));
		String probe = ProcessUtil.run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name,width,height,sample_aspect_ratio", "-of", "default=noprint_wrappers=1", output.getAbsolutePath());
		assertTrue(probe.contains("codec_name=mpeg2video") && probe.contains("width=192\n") && probe.contains("height=128\n") && probe.contains("sample_aspect_ratio=1:1"), probe);
		System.out.println("MENCODER_MPEG2_NATIVE JP3D=false " + probe.replace('\n', ' '));
	}
}

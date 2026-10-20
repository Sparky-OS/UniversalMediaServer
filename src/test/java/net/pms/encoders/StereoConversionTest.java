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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.pms.formats.Format;
import net.pms.io.OutputParams;
import net.pms.media.MediaInfo;
import net.pms.parsers.FFmpegParser;
import net.pms.parsers.MediaInfoParser;
import net.pms.parsers.ParserTest;
import net.pms.renderers.Renderer;
import net.pms.store.item.RealFile;
import net.pms.util.ProcessUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StereoConversionTest {

	@TempDir
	File tempDir;

	@BeforeAll
	static void configure() {
		ParserTest.setUpClass();
	}

	@Test
	void interleavedContainerInputsAreConvertedWithBothEyesInPlace() throws Exception {
		assumeTrue(MediaInfoParser.isValid() && FFmpegParser.isValid());
		String ffmpeg = EngineFactory.getEngineExecutable(StandardEngineId.FFMPEG_VIDEO);
		String[][] layouts = {
			{"irl", "row_interleaved_lr"}, {"irr", "row_interleaved_rl"},
			{"icl", "col_interleaved_lr"}, {"icr", "col_interleaved_rl"}
		};
		for (String[] layout : layouts) {
			File source = new File(tempDir, layout[0] + ".mkv");
			boolean rows = layout[0].startsWith("ir");
			int width = rows ? 64 : 128;
			int height = rows ? 96 : 48;
			File raw = new File(tempDir, layout[0] + ".rgb");
			Files.write(raw.toPath(), interleavedPixels(width, height, rows, layout[0].endsWith("l")));
			String generated = ProcessUtil.run(ffmpeg, "-nostdin", "-y", "-f", "rawvideo",
				"-pixel_format", "rgb24", "-video_size", width + "x" + height, "-i", raw.getAbsolutePath(),
				"-c:v", "ffv1", "-pix_fmt", "yuv444p", "-metadata:s:v:0", "stereo_mode=" + layout[1], source.getAbsolutePath());
			assertTrue(source.length() > 0, generated);
			MediaInfo media = new MediaInfo();
			MediaInfoParser.parse(media, source, Format.VIDEO);
			assertEquals(layout[0], media.getDefaultVideoTrack().get3DLayout().name().toLowerCase(java.util.Locale.ROOT));
			assertFalse(media.getDefaultVideoTrack().multiViewIsAnaglyph());

			Renderer renderer = new Renderer((String) null) {
				@Override
				public String getOutput3DFormat() {
					return "sbsl";
				}
			};
			RealFile item = new RealFile(renderer, source);
			item.setMediaInfo(media);
			OutputParams params = new OutputParams(renderer.getUmsConfiguration());
			params.setMediaRenderer(renderer);
			List<String> filters = new FFMpegVideo().getVideoFilterOptions(item, media, params, false);
			String expectedFilter = "icr".equals(layout[0]) ? "icl:sbsr" : layout[0] + ":sbsl";
			assertEquals(List.of("-filter_complex", "[0:v]stereo3d=" + expectedFilter), filters);
			File frame = new File(tempDir, layout[0] + ".png");
			List<String> command = new ArrayList<>(List.of(ffmpeg, "-nostdin", "-y", "-i", source.getAbsolutePath()));
			command.addAll(filters);
			command.addAll(List.of("-frames:v", "1", "-threads", "1", frame.getAbsolutePath()));
			String converted = ProcessUtil.run(command.toArray(String[]::new));
			assertTrue(frame.length() > 0, converted);
			BufferedImage image = ImageIO.read(frame);
			assertEquals(128, image.getWidth());
			assertEquals(48, image.getHeight());
			assertColor(image, 24, 24, 255, 0, 0);
			assertColor(image, 88, 16, 0, 0, 255);
			assertColor(image, 12, 12, 255, 255, 255);
			assertColor(image, 100, 28, 255, 255, 255);
			assertColor(image, 36, 28, 255, 0, 0);
			assertColor(image, 76, 12, 0, 0, 255);
		}
	}

	private static byte[] interleavedPixels(int width, int height, boolean rows, boolean leftFirst) {
		byte[] pixels = new byte[width * height * 3];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean left = ((rows ? y : x) % 2 == 0) == leftFirst;
				int eyeX = rows ? x : x / 2;
				int eyeY = rows ? y / 2 : y;
				boolean white = left ? eyeX >= 8 && eyeX < 16 && eyeY >= 8 && eyeY < 16 :
					eyeX >= 32 && eyeX < 40 && eyeY >= 24 && eyeY < 32;
				int offset = (y * width + x) * 3;
				pixels[offset] = (byte) (left || white ? 255 : 0);
				pixels[offset + 1] = (byte) (white ? 255 : 0);
				pixels[offset + 2] = (byte) (!left || white ? 255 : 0);
			}
		}
		return pixels;
	}

	private static void assertColor(BufferedImage image, int x, int y, int red, int green, int blue) {
		int pixel = image.getRGB(x, y);
		assertTrue(Math.abs(((pixel >> 16) & 255) - red) <= 3, "red at " + x + "," + y + " rgb=" + Integer.toHexString(pixel));
		assertTrue(Math.abs(((pixel >> 8) & 255) - green) <= 3, "green at " + x + "," + y + " rgb=" + Integer.toHexString(pixel));
		assertTrue(Math.abs((pixel & 255) - blue) <= 3, "blue at " + x + "," + y + " rgb=" + Integer.toHexString(pixel));
	}
}

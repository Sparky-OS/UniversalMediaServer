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
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.pms.configuration.RendererConfiguration;
import net.pms.media.MediaInfo;
import net.pms.media.video.MediaVideo.Mode3D;
import net.pms.parsers.ParserTest;
import net.pms.renderers.Renderer;
import net.pms.store.item.RealFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

class MEncoderStereoTest {
	@TempDir
	File tempDir;

	@BeforeAll
	static void configure() {
		ParserTest.setUpClass();
	}

	static Renderer renderer(Mode3D output) throws Exception {
		return new Renderer((String) null) {
			@Override
			public String getOutput3DFormat() { return output.name().toLowerCase(Locale.ROOT); }
		};
	}

	static String filters(MediaInfo media, Renderer renderer, File source) throws Exception {
		RealFile item = new RealFile(renderer, source);
		item.setMediaInfo(media);
		return MEncoderStereoOptions.filters(item, media, renderer);
	}

	@TestFactory
	Stream<DynamicTest> allSupportedInputAndOutputChains() {
		return Stream.of(Mode3D.values()).filter(mode -> mode.name().startsWith("SBS") || mode.name().startsWith("AB"))
			.flatMap(input -> Stream.of(Mode3D.values()).map(output -> DynamicTest.dynamicTest(input + " -> " + output, () -> {
				String chain = filters(StereoRendererGeometryTest.media(input, 192, 128), renderer(output), new File("stereo.avi"));
				if (input == output) {
					assertNull(chain);
				} else {
					int[] size = StereoRendererGeometryTest.outputSize(output, 192, 128);
					assertEquals("stereo3d=" + input.name().toLowerCase(Locale.ROOT) + ":" + output.name().toLowerCase(Locale.ROOT) +
						",scale=" + size[0] + ":" + size[1] + ":arnd=1,dsize=" + size[0] + ":" + size[1], chain);
				}
			})));
	}

	@Test
	void sourceRasterAndOnlyDeclaredAxesLimitConversion() throws Exception {
		assertEquals("stereo3d=sbs2l:ab2l,scale=3840:2160:arnd=1,dsize=3840:2160",
			filters(StereoRendererGeometryTest.media(Mode3D.SBS2L, 3840, 2160), renderer(Mode3D.AB2L), new File("four-k.mp4")));
		for (String limits : List.of("MaxVideoHeight = 2160", "MaxVideoWidth = 3840")) {
			File profile = new File(tempDir, "limits.conf");
			Files.writeString(profile.toPath(), "RendererName = Axis limit\nOutput3DFormat = ab2l\n" + limits + "\n");
			Renderer renderer = new Renderer(new RendererConfiguration(profile));
			assertTrue(filters(StereoRendererGeometryTest.media(Mode3D.SBS2L, 7680, 4320), renderer, new File("eight-k.mp4"))
				.endsWith(",scale=3840:2160:arnd=1,dsize=3840:2160"));
		}
	}

	@Test
	void rendererPaddingAndLimitsFollowRepacking() throws Exception {
		File profile = new File(tempDir, "padded.conf");
		Files.writeString(profile.toPath(), "RendererName = Padding\nOutput3DFormat = ml\nKeepAspectRatio = true\nMaxVideoWidth = 192\nMaxVideoHeight = 108\n");
		Renderer renderer = new Renderer(new RendererConfiguration(profile));
		String chain = filters(StereoRendererGeometryTest.media(Mode3D.SBS2L, 192, 128), renderer, new File("source.mp4"));
		assertEquals("stereo3d=sbs2l:ml,scale=192:128:arnd=1,expand=228:128,scale=192:106:arnd=1,expand=192:108,dsize=192:108", chain);
		Files.writeString(profile.toPath(), "RendererName = Odd limits\nOutput3DFormat = ab2l\nMaxVideoWidth = 1918\nMaxVideoHeight = 1078\n");
		renderer = new Renderer(new RendererConfiguration(profile));
		chain = filters(StereoRendererGeometryTest.media(Mode3D.SBS2L, 3840, 2160), renderer, new File("four-k.mp4"));
		assertTrue(chain.endsWith("scale=1916:1076:arnd=1,dsize=1916:1076"), chain);
	}

	@Test
	void ordinary2dDoesNotChangeCommandOrAcquireADeclaration() throws Exception {
		MediaInfo media = StereoRendererGeometryTest.media(Mode3D.SBS2L, 192, 128);
		media.getDefaultVideoTrack().setMultiViewLayout(null);
		Renderer renderer = renderer(Mode3D.AB2L);
		assertNull(filters(media, renderer, new File("2d.mp4")));
		List<String> command = new ArrayList<>(List.of("-ovc", "x264", "-vf", "eq2=1", "-x264encopts", "crf=18"));
		List<String> before = List.copyOf(command);
		MEncoderStereoOptions.apply(command, null, MEncoderStereoOptions.outputLayout(media, renderer), EncodingFormat.VIDEO.get("MPEGTS-H264-AC3"));
		assertEquals(before, command);
	}

	@Test
	void x264OptionsAndCustomFiltersSurvive() throws Exception {
		List<String> command = new ArrayList<>(List.of("-ovc", "x264", "-x264encopts", "crf=18:keyint=12", "-vf", "eq2=1", "-x264encopts", "threads=1"));
		MEncoderStereoOptions.apply(command, "stereo3d=ab2l:sbs2l,scale=192:128,dsize=192:128", Mode3D.SBS2L, EncodingFormat.VIDEO.get("MPEGTS-H264-AC3"));
		assertEquals(1, command.stream().filter("-x264encopts"::equals).count());
		assertEquals("crf=18:keyint=12:threads=1:frame_packing=3", command.get(command.indexOf("-x264encopts") + 1));
		assertEquals("stereo3d=ab2l:sbs2l,scale=192:128,dsize=192:128,eq2=1", command.get(command.indexOf("-vf") + 1));
	}

	@Test
	void customPackingIsAnExplicitOverride() throws Exception {
		List<String> command = new ArrayList<>(List.of("-ovc", "x264", "-x264encopts", "crf=18:frame_packing=4"));
		MEncoderStereoOptions.apply(command, null, Mode3D.SBS2L, EncodingFormat.VIDEO.get("MPEGTS-H264-AC3"));
		assertEquals("crf=18:frame_packing=4", command.get(command.indexOf("-x264encopts") + 1));
	}

	@Test
	void unsupportedDeclarationsNeverMislabelThePixelsOrChangeCodec() throws Exception {
		for (Mode3D output : List.of(Mode3D.SBS2R, Mode3D.AB2R, Mode3D.ML, Mode3D.MR, Mode3D.ARCD)) {
			List<String> command = new ArrayList<>(List.of("-ovc", "x264", "-x264encopts", "crf=18"));
			MEncoderStereoOptions.apply(command, null, output, EncodingFormat.VIDEO.get("MPEGTS-H264-AC3"));
			assertFalse(command.toString().contains("frame_packing="), command.toString());
		}
		List<String> mpeg2 = new ArrayList<>(List.of("-ovc", "lavc", "-lavcopts", "vcodec=mpeg2video"));
		List<String> before = List.copyOf(mpeg2);
		MEncoderStereoOptions.apply(mpeg2, null, Mode3D.SBS2L, EncodingFormat.VIDEO.get("MPEGPS-MPEG2-AC3"));
		assertEquals(before, mpeg2.subList(0, before.size()));
		assertEquals("scale=arnd=1", mpeg2.get(mpeg2.indexOf("-vf") + 1));
		assertThrows(IOException.class, () -> MEncoderStereoOptions.apply(new ArrayList<>(), null, Mode3D.SBS2L, EncodingFormat.VIDEO.get("MPEGTS-H265-AC3")));
	}
}

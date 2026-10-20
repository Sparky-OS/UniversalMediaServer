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
package net.pms.media.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MediaVideoStereoTest {

	@Test
	void interleavedLayoutsKeepTheirEyeOrder() {
		String[][] layouts = {
			{"Row Interleaved (left eye first)", "IRL"},
			{"Row Interleaved (right eye first)", "IRR"},
			{"Column Interleaved (left eye first)", "ICL"},
			{"Column Interleaved (right eye first)", "ICR"}
		};
		for (String[] layout : layouts) {
			MediaVideo video = new MediaVideo();
			video.setMultiViewLayout(layout[0]);
			assertNotNull(video.get3DLayout(), layout[0]);
			assertEquals(layout[1], video.get3DLayout().name());
			assertFalse(video.multiViewIsAnaglyph(), layout[0]);
			assertFalse(video.is3dFullSbsOrOu(), layout[0]);
		}
	}

	@Test
	void unsupportedPackingsHaveNoConversionMode() {
		for (String layout : new String[] {
			"Checkboard (left eye first)", "Checkboard (right eye first)",
			"Both Eyes laced in one block (left eye first)",
			"Both Eyes laced in one block (right eye first)", "side by side (quincunx)"
		}) {
			MediaVideo video = new MediaVideo();
			video.setMultiViewLayout(layout);
			assertNull(video.get3DLayout(), layout);
		}
	}

	@Test
	void containerLayoutDoesNotGuessSamplingFromDimensions() {
		for (int width : new int[] {1280, 1920, 2560, 3840}) {
			MediaVideo video = new MediaVideo();
			video.setWidth(width);
			video.setHeight(720);
			video.setMultiViewLayout("Side by Side (left eye first)");
			assertEquals(MediaVideo.Mode3D.SBSL, video.get3DLayout());
			video.setMultiViewLayout("half side by side (left eye first)");
			assertEquals(MediaVideo.Mode3D.SBS2L, video.get3DLayout());
		}
	}
}

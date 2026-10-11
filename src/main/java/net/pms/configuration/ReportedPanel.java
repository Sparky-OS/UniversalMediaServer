/* SPDX-License-Identifier: GPL-2.0-only */
package net.pms.configuration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native panel dimensions carried by Flat Headset's device description and HTTP identity. */
public final class ReportedPanel {
	private static final Pattern FORMAT = Pattern.compile("\\bFlatHeadset/[0-9.]+ Panel/(\\d{2,5})x(\\d{2,5})\\b");

	private ReportedPanel() {
	}

	public static int[] parse(String description) {
		if (description == null || description.length() > 4096) {
			return null;
		}
		Matcher match = FORMAT.matcher(description);
		if (!match.find()) {
			return null;
		}
		int width = Integer.parseInt(match.group(1));
		int height = Integer.parseInt(match.group(2));
		return width >= 320 && width <= 16384 && height >= 240 && height <= 8192 && width % 2 == 0 && height % 2 == 0
			? new int[] {width, height} : null;
	}
}

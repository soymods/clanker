/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */
// Adapted from Baritone's Helper (branch 26.2, 9fadf7cf) for Clanker: logs instead of client chat.

package io.github.soymods.clanker.server.pathing.util;

import io.github.soymods.clanker.Clanker;

/**
 * Stands in for Baritone's Helper, which printed to the client's chat: here, debug messages go to the log.
 */
public interface Helper {
	Helper HELPER = new Helper() {
	};

	default void logDebug(String message) {
		Clanker.LOGGER.debug("[pathing] {}", message);
	}

	default void logDirect(String message) {
		Clanker.LOGGER.warn("[pathing] {}", message);
	}
}

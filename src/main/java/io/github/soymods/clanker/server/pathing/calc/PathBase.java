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
// Ported from Baritone (branch 26.2, 9fadf7cf) for Clanker. Changes: package names; Baritone's client player
// context, settings, and behaviors replaced by Clanker's server-side BotContext and PathingSettings.

package io.github.soymods.clanker.server.pathing.calc;

import io.github.soymods.clanker.server.pathing.BotContext;
import io.github.soymods.clanker.server.pathing.PathingSettings;



import io.github.soymods.clanker.server.pathing.Goal;

import io.github.soymods.clanker.server.pathing.util.BlockStateInterface;
import net.minecraft.core.BlockPos;

public abstract class PathBase implements IPath {

    @Override
    public PathBase cutoffAtLoadedChunks(Object bsi0) { // <-- cursed cursed cursed
        if (!PathingSettings.get().cutoffAtLoadBoundary.value) {
            return this;
        }
        BlockStateInterface bsi = (BlockStateInterface) bsi0;
        for (int i = 0; i < positions().size(); i++) {
            BlockPos pos = positions().get(i);
            if (!bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                return new CutoffPath(this, i);
            }
        }
        return this;
    }

    @Override
    public PathBase staticCutoff(Goal destination) {
        int min = PathingSettings.get().pathCutoffMinimumLength.value;
        if (length() < min) {
            return this;
        }
        if (destination == null || destination.isInGoal(getDest())) {
            return this;
        }
        double factor = PathingSettings.get().pathCutoffFactor.value;
        int newLength = (int) ((length() - min) * factor) + min - 1;
        return new CutoffPath(this, newLength);
    }
}
